package com.github.vladsaraykin.aichat.agent;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.agent.infrastructure.FileChatRepository;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

class InvariantsTest {
    @TempDir Path directory;
    private static ChatMessage.Metrics metrics() {
        return new ChatMessage.Metrics("test-model", 10, 2, 3, 4, 10, 0, 5, 15,
                new BigDecimal("0.01"), new BigDecimal("0.02"), new BigDecimal("0.03"), "stop");
    }
    private static AgentDefinition definition() {
        return new AgentDefinition("guarded", "Guarded", "Guarded agent", "test-model", "system",
                1000, null, 30, 10000, new TokenPricing(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE),
                AgentDefinition.ContextCompression.disabled(), AgentDefinition.ContextManagement.defaults(),
                AgentDefinition.MemoryLayers.disabled(), new AgentDefinition.InvariantSettings(true, 200, "guard"));
    }
    private static final class GuardedAgent implements Agent {
        private final AtomicInteger answers = new AtomicInteger();
        private String answer = "Решение на PostgreSQL";
        @Override public AgentDefinition definition() { return InvariantsTest.definition(); }
        @Override public ChatMessage answer(List<ChatMessage> history, ChatMessage userMessage) { throw new UnsupportedOperationException(); }
        @Override public Flux<AnswerPart> answerStream(List<ChatMessage> history, ChatMessage userMessage) {
            answers.incrementAndGet();
            var completed = new ChatMessage(UUID.randomUUID(), ChatMessage.Role.ASSISTANT, answer, Instant.now(), metrics());
            return Flux.just(AnswerPart.delta(answer), AnswerPart.completed(completed));
        }
        @Override public Mono<ContextSummary> summarize(ContextSummary previous, List<ChatMessage> messages) {
            return Mono.error(new UnsupportedOperationException());
        }
        @Override public Mono<InvariantCheck> checkInvariants(Chat chat, ChatMessage user, ChatMessage assistant) {
            String content = assistant == null ? user.content() : assistant.content();
            boolean conflict = content.contains("MongoDB");
            if (!conflict) return Mono.just(InvariantCheck.allowed(metrics()));
            var entry = chat.invariants().entries().getFirst();
            return Mono.just(new InvariantCheck(false, List.of(new InvariantConflict(entry.id(), "MongoDB",
                    "MongoDB противоречит зафиксированному стеку.")), metrics()));
        }
    }
    private record Fixture(ChatService service, GuardedAgent agent) { }
    private Fixture fixture() throws Exception {
        var agent = new GuardedAgent();
        AgentCatalog catalog = new AgentCatalog() {
            public List<AgentDefinition> definitions() { return List.of(agent.definition()); }
            public Agent get(String id) {
                if (!id.equals("guarded")) throw new ChatFailure(ChatFailure.Kind.NOT_FOUND, "Агент не найден");
                return agent;
            }
        };
        return new Fixture(new ChatService(catalog, new FileChatRepository(directory.toString())), agent);
    }
    private Chat withInvariant(Fixture fixture) {
        Chat chat = fixture.service().create("guarded", ContextStrategyType.SLIDING_WINDOW);
        var entry = new TaskInvariants.Entry(UUID.randomUUID(), TaskInvariants.Type.STACK_CONSTRAINT,
                "Основная БД", "Использовать только PostgreSQL", "Решение команды", Instant.now());
        return fixture.service().putInvariant(ChatRepository.LEGACY_OWNER, "guarded", chat.id(), 0, entry);
    }

    @Test void persistsCrudAndCopiesRulesIndependentlyToBranches() throws Exception {
        Fixture fixture = fixture();
        Chat chat = withInvariant(fixture);
        assertThat(chat.invariants().version()).isEqualTo(1);
        Chat restored = new FileChatRepository(directory.toString()).get("guarded", chat.id());
        assertThat(restored.invariants().entries()).extracting(TaskInvariants.Entry::rule)
                .containsExactly("Использовать только PostgreSQL");
        assertThatThrownBy(() -> fixture.service().putInvariant(ChatRepository.LEGACY_OWNER, "guarded", chat.id(), 0,
                chat.invariants().entries().getFirst())).hasMessageContaining("изменились");
        Chat deleted = fixture.service().deleteInvariant(ChatRepository.LEGACY_OWNER, "guarded", chat.id(), 1,
                chat.invariants().entries().getFirst().id());
        assertThat(deleted.invariants().entries()).isEmpty();

        var user = new ChatMessage(UUID.randomUUID(), ChatMessage.Role.USER, "start", Instant.now(), null);
        var assistant = new ChatMessage(UUID.randomUUID(), ChatMessage.Role.ASSISTANT, "ok", Instant.now(), metrics());
        Chat forked = chat.append(user, assistant).fork("A", "B");
        Chat changedA = forked.branches().getFirst().withInvariants(
                forked.branches().getFirst().invariants().delete(chat.invariants().entries().getFirst().id()));
        assertThat(changedA.invariants().entries()).isEmpty();
        assertThat(forked.branches().get(1).invariants().entries()).hasSize(1);
    }

    @Test void blocksConflictingRequestBeforeGenerationAndPreservesTaskState() throws Exception {
        Fixture fixture = fixture();
        Chat chat = withInvariant(fixture);
        var events = fixture.service().stream("guarded", chat.id(), UUID.randomUUID(),
                "Давайте заменим PostgreSQL на MongoDB").collectList().block();
        Chat saved = events.getLast().chat();
        assertThat(events).extracting(ChatService.StreamEvent::type).contains(
                ChatService.StreamEvent.Type.CHECKING_INVARIANTS, ChatService.StreamEvent.Type.DELTA,
                ChatService.StreamEvent.Type.COMPLETED);
        assertThat(fixture.agent().answers).hasValue(0);
        assertThat(saved.messages().getLast().content()).contains("Не могу", "Основная БД", "Использовать только PostgreSQL");
        assertThat(saved.workingMemory()).isEqualTo(chat.workingMemory());
    }

    @Test void validatesDraftBeforeReplayingItAndAccountsForGuardCalls() throws Exception {
        Fixture fixture = fixture();
        Chat chat = withInvariant(fixture);
        var events = fixture.service().stream("guarded", chat.id(), UUID.randomUUID(),
                "Предложи схему таблиц PostgreSQL").collectList().block();
        Chat saved = events.getLast().chat();
        assertThat(events).extracting(ChatService.StreamEvent::type).containsSubsequence(
                ChatService.StreamEvent.Type.CHECKING_INVARIANTS, ChatService.StreamEvent.Type.GENERATING,
                ChatService.StreamEvent.Type.VALIDATING_ANSWER, ChatService.StreamEvent.Type.DELTA,
                ChatService.StreamEvent.Type.COMPLETED);
        assertThat(saved.messages().getLast().content()).isEqualTo("Решение на PostgreSQL");
        assertThat(saved.invariants().usage().calls()).isEqualTo(2);
        assertThat(saved.invariants().usage().totalTokens()).isEqualTo(30);
    }

    @Test void hidesDraftThatViolatesInvariant() throws Exception {
        Fixture fixture = fixture();
        Chat chat = withInvariant(fixture);
        fixture.agent().answer = "Используйте MongoDB";
        var events = fixture.service().stream("guarded", chat.id(), UUID.randomUUID(),
                "Предложи ещё один вариант").collectList().block();
        Chat saved = events.getLast().chat();
        assertThat(events.stream().filter(event -> event.type() == ChatService.StreamEvent.Type.DELTA)
                .map(ChatService.StreamEvent::text)).allMatch(text -> !text.contains("Используйте MongoDB"));
        assertThat(saved.messages().getLast().content()).contains("Не могу", "Основная БД");
        assertThat(saved.invariants().usage().calls()).isEqualTo(2); // request guard + discarded draft
    }

    @Test void configuredAgentValidatesGuardJsonIdsAndExactEvidence() {
        var entry = new TaskInvariants.Entry(UUID.randomUUID(), TaskInvariants.Type.BUSINESS_RULE,
                "Email only", "Отправлять только email", "MVP", Instant.now());
        Chat chat = Chat.create("guarded").withInvariants(TaskInvariants.empty().put(entry));
        ConversationModel model = new ConversationModel() {
            public Reply reply(AgentDefinition ignored, List<ChatMessage> messages) { throw new UnsupportedOperationException(); }
            @Override public Flux<StreamPart> checkInvariants(AgentDefinition ignored, List<ChatMessage> messages) {
                String json = "{\"result\":\"CONFLICT\",\"conflicts\":[{\"invariantId\":\"" + entry.id()
                        + "\",\"evidence\":\"SMS\",\"explanation\":\"Канал запрещён\"}]}";
                return Flux.just(StreamPart.completed(new Reply(json, metrics())));
            }
        };
        var agent = new ConfiguredAgent(definition(), model);
        var user = new ChatMessage(UUID.randomUUID(), ChatMessage.Role.USER, "Добавим SMS", Instant.now(), null);
        Agent.InvariantCheck result = agent.checkInvariants(chat, user, null).block();
        assertThat(result.allowed()).isFalse();
        assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.invariantId()).isEqualTo(entry.id());
            assertThat(conflict.evidence()).isEqualTo("SMS");
        });
    }

    @Test void malformedGuardResultFailsClosed() {
        var entry = new TaskInvariants.Entry(UUID.randomUUID(), TaskInvariants.Type.ARCHITECTURE,
                "Монолит", "Сохранять модульный монолит", "Команда", Instant.now());
        Chat chat = Chat.create("guarded").withInvariants(TaskInvariants.empty().put(entry));
        ConversationModel model = new ConversationModel() {
            public Reply reply(AgentDefinition ignored, List<ChatMessage> messages) { throw new UnsupportedOperationException(); }
            @Override public Flux<StreamPart> checkInvariants(AgentDefinition ignored, List<ChatMessage> messages) {
                return Flux.just(StreamPart.completed(new Reply("not-json", metrics())));
            }
        };
        var user = new ChatMessage(UUID.randomUUID(), ChatMessage.Role.USER, "Развиваем архитектуру", Instant.now(), null);
        assertThatThrownBy(() -> new ConfiguredAgent(definition(), model).checkInvariants(chat, user, null).block())
                .isInstanceOf(ChatFailure.class).hasMessageContaining("Ответ заблокирован");
    }
}
