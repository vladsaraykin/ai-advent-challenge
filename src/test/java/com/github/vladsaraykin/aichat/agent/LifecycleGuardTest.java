package com.github.vladsaraykin.aichat.agent;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.agent.infrastructure.FileChatRepository;
import com.github.vladsaraykin.aichat.agent.infrastructure.FileLongTermMemoryRepository;
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

class LifecycleGuardTest {
    @TempDir Path directory;

    private static ChatMessage.Metrics metrics() {
        return new ChatMessage.Metrics("test-model", 10, 2, 3, 4, 10, 0, 5, 15,
                new BigDecimal("0.01"), new BigDecimal("0.02"), new BigDecimal("0.03"), "stop");
    }

    private static AgentDefinition definition() {
        return new AgentDefinition("architect", "Architect", "Lifecycle agent", "test-model", "system",
                1000, null, 30, 10000, new TokenPricing(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE),
                AgentDefinition.ContextCompression.disabled(), AgentDefinition.ContextManagement.defaults(),
                new AgentDefinition.MemoryLayers(true, 10, 1000, "memory"),
                AgentDefinition.InvariantSettings.disabled(),
                new AgentDefinition.LifecycleSettings(true, 200, "lifecycle guard"));
    }

    private static final class GuardedAgent implements Agent {
        private final AtomicInteger answers = new AtomicInteger();
        private String answer = "Реализация соответствует утверждённому плану";
        @Override public AgentDefinition definition() { return LifecycleGuardTest.definition(); }
        @Override public ChatMessage answer(List<ChatMessage> history, ChatMessage userMessage) {
            throw new UnsupportedOperationException();
        }
        @Override public Flux<AnswerPart> answerStream(List<ChatMessage> history, ChatMessage userMessage) {
            answers.incrementAndGet();
            var completed = new ChatMessage(UUID.randomUUID(), ChatMessage.Role.ASSISTANT, answer, Instant.now(), metrics());
            return Flux.just(AnswerPart.delta(answer), AnswerPart.completed(completed));
        }
        @Override public Mono<ContextSummary> summarize(ContextSummary previous, List<ChatMessage> messages) {
            return Mono.error(new UnsupportedOperationException());
        }
        @Override public Mono<LifecycleCheck> checkLifecycle(Chat chat, ChatMessage user, ChatMessage assistant) {
            String content = assistant == null ? user.content() : assistant.content();
            if (chat.workingMemory().stage() == WorkingMemory.Stage.REQUIREMENTS
                    && content.contains("реализац")) {
                return Mono.just(LifecycleCheck.blocked("PREMATURE_EXECUTION", "реализацию",
                        "Реализация доступна только после подтверждения требований.", metrics()));
            }
            if (content.contains("задача завершена")) {
                return Mono.just(LifecycleCheck.blocked("PREMATURE_COMPLETION", "задача завершена",
                        "Финал доступен только после проверки.", metrics()));
            }
            return Mono.just(LifecycleCheck.allowed(metrics()));
        }
    }

    private record Fixture(ChatService service, GuardedAgent agent) { }
    private Fixture fixture() throws Exception {
        var agent = new GuardedAgent();
        AgentCatalog catalog = new AgentCatalog() {
            public List<AgentDefinition> definitions() { return List.of(agent.definition()); }
            public Agent get(String id) { return agent; }
        };
        return new Fixture(new ChatService(catalog, new FileChatRepository(directory.resolve("chats").toString()),
                new FileLongTermMemoryRepository(directory.resolve("memory").toString())), agent);
    }

    @Test void blocksImplementationBeforeApprovedRequirementsAndPreservesState() throws Exception {
        Fixture fixture = fixture();
        Chat chat = fixture.service().create("architect", ContextStrategyType.SLIDING_WINDOW);
        chat = fixture.service().editTask("architect", chat.id(), 0, "project",
                new MemoryService.TaskData("Сервис", Map.of("channel", "email"), Map.of(), Map.of(), List.of()));
        var events = fixture.service().stream("architect", chat.id(), UUID.randomUUID(),
                "Напиши реализацию сервиса").collectList().block();
        Chat saved = events.getLast().chat();

        assertThat(events).extracting(ChatService.StreamEvent::type).containsSubsequence(
                ChatService.StreamEvent.Type.STARTED, ChatService.StreamEvent.Type.CHECKING_LIFECYCLE,
                ChatService.StreamEvent.Type.DELTA, ChatService.StreamEvent.Type.COMPLETED);
        assertThat(fixture.agent().answers).hasValue(0);
        assertThat(saved.workingMemory()).isEqualTo(chat.workingMemory());
        assertThat(saved.messages().getLast().content()).contains("Planning", "подтвердите требования");
    }

    @Test void allowsExecutionAfterExplicitTransitionAndAccountsForBothChecks() throws Exception {
        Fixture fixture = fixture();
        Chat chat = fixture.service().create("architect", ContextStrategyType.SLIDING_WINDOW);
        chat = fixture.service().editTask("architect", chat.id(), 0, "project",
                new MemoryService.TaskData("Сервис", Map.of("channel", "email"), Map.of(), Map.of(), List.of()));
        chat = fixture.service().advanceTask("architect", chat.id(), chat.workingMemory().version());

        var events = fixture.service().stream("architect", chat.id(), UUID.randomUUID(),
                "Подготовь реализацию").collectList().block();
        Chat saved = events.getLast().chat();
        assertThat(events).extracting(ChatService.StreamEvent::type).containsSubsequence(
                ChatService.StreamEvent.Type.CHECKING_LIFECYCLE, ChatService.StreamEvent.Type.GENERATING,
                ChatService.StreamEvent.Type.VALIDATING_LIFECYCLE, ChatService.StreamEvent.Type.DELTA,
                ChatService.StreamEvent.Type.COMPLETED);
        assertThat(saved.messages().getLast().content()).isEqualTo("Реализация соответствует утверждённому плану");
        assertThat(saved.lifecycle().usage().calls()).isEqualTo(2);
    }

    @Test void hidesDraftThatClaimsPrematureCompletion() throws Exception {
        Fixture fixture = fixture();
        Chat chat = fixture.service().create("architect", ContextStrategyType.SLIDING_WINDOW);
        chat = fixture.service().editTask("architect", chat.id(), 0, "project",
                new MemoryService.TaskData("Сервис", Map.of("channel", "email"), Map.of(), Map.of(), List.of()));
        chat = fixture.service().advanceTask("architect", chat.id(), chat.workingMemory().version());
        fixture.agent().answer = "задача завершена";

        var events = fixture.service().stream("architect", chat.id(), UUID.randomUUID(), "Продолжай")
                .collectList().block();
        assertThat(events.stream().filter(event -> event.type() == ChatService.StreamEvent.Type.DELTA)
                .map(ChatService.StreamEvent::text)).allMatch(text -> !text.equals("задача завершена"));
        assertThat(events.getLast().chat().messages().getLast().content()).contains("Execution", "после проверки");
        assertThat(events.getLast().chat().workingMemory().stage()).isEqualTo(WorkingMemory.Stage.DESIGN);
    }

    @Test void configuredAgentRejectsMalformedLifecycleDecision() {
        ConversationModel model = new ConversationModel() {
            public Reply reply(AgentDefinition ignored, List<ChatMessage> messages) { throw new UnsupportedOperationException(); }
            @Override public Flux<StreamPart> checkLifecycle(AgentDefinition ignored, List<ChatMessage> messages) {
                return Flux.just(StreamPart.completed(new Reply("not-json", metrics())));
            }
        };
        Chat chat = Chat.create("architect");
        var user = new ChatMessage(UUID.randomUUID(), ChatMessage.Role.USER, "Обсудим план", Instant.now(), null);
        assertThatThrownBy(() -> new ConfiguredAgent(definition(), model).checkLifecycle(chat, user, null).block())
                .isInstanceOf(ChatFailure.class).hasMessageContaining("Ответ заблокирован");
    }

    @Test void configuredAgentValidatesLifecycleCodeAndExactEvidence() {
        ConversationModel model = new ConversationModel() {
            public Reply reply(AgentDefinition ignored, List<ChatMessage> messages) { throw new UnsupportedOperationException(); }
            @Override public Flux<StreamPart> checkLifecycle(AgentDefinition ignored, List<ChatMessage> messages) {
                return Flux.just(StreamPart.completed(new Reply("""
                        {"result":"BLOCK","violation":{"code":"PREMATURE_EXECUTION",
                        "evidence":"Напиши код","explanation":"Требования ещё не подтверждены."}}
                        """, metrics())));
            }
        };
        Chat chat = Chat.create("architect");
        var user = new ChatMessage(UUID.randomUUID(), ChatMessage.Role.USER, "Напиши код сервиса", Instant.now(), null);
        Agent.LifecycleCheck result = new ConfiguredAgent(definition(), model).checkLifecycle(chat, user, null).block();
        assertThat(result.allowed()).isFalse();
        assertThat(result.violation().code()).isEqualTo("PREMATURE_EXECUTION");
        assertThat(result.violation().evidence()).isEqualTo("Напиши код");
    }
}
