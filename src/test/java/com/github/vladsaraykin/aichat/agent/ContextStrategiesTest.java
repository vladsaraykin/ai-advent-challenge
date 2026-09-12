package com.github.vladsaraykin.aichat.agent;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.agent.infrastructure.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

class ContextStrategiesTest {
    @TempDir Path directory;
    private static ConversationModel.Reply reply(String text) {
        return new ConversationModel.Reply(text, new ChatMessage.Metrics("gpt-4.1-mini", 10,
                2, 3, 5, 10, 0, 20, 30, new BigDecimal("0.000004"),
                new BigDecimal("0.000032"), new BigDecimal("0.000036"), "stop"));
    }
    private ChatService service(ConversationModel model) throws Exception {
        return new ChatService(new AgentRegistry(model, "classpath:agents/*.yaml"), new FileChatRepository(directory.toString()));
    }
    private static boolean extracting(AgentDefinition d) { return d.systemPrompt().equals(d.contextManagement().factsPrompt()); }

    @Test void fifteenTurnScenarioExercisesAllStrategiesAndPreservesAccounting() throws Exception {
        var inputs = new ArrayList<List<ChatMessage>>();
        var prompts = new ArrayList<String>();
        AtomicInteger extractions = new AtomicInteger();
        ConversationModel model = new ConversationModel() {
            public Reply reply(AgentDefinition d, List<ChatMessage> messages) {
                if (extracting(d)) {
                    extractions.incrementAndGet();
                    return ContextStrategiesTest.reply("{\"goal\":\"Магазин\",\"database\":\"PostgreSQL\"}");
                }
                inputs.add(messages);
                prompts.add(d.systemPrompt());
                return ContextStrategiesTest.reply("Уточнение ТЗ");
            }
            public Mono<Reply> summarize(AgentDefinition d, ContextSummary previous, List<ChatMessage> messages) {
                return Mono.just(ContextStrategiesTest.reply("Цель: магазин, БД PostgreSQL"));
            }
        };
        ChatService service = service(model);
        for (ContextStrategyType type : ContextStrategyType.values()) {
            inputs.clear(); prompts.clear();
            Chat chat = service.create("architect", type);
            UUID firstId = UUID.randomUUID();
            for (int i = 0; i < 15; i++) {
                chat = service.send("architect", chat.id(), i == 0 ? firstId : UUID.randomUUID(),
                        i == 0 ? "Цель: магазин, БД PostgreSQL" : "Уточнение ТЗ " + i);
            }
            assertThat(chat.strategy()).isEqualTo(type);
            assertThat(chat.messageCount()).isEqualTo(30);
            assertThat(inputs).hasSize(15);
            assertThat(chat.messages().size() % 2).isZero();
            if (type == ContextStrategyType.BRANCHING) {
                assertThat(chat.messages()).hasSize(30);
                assertThat(inputs.getLast()).hasSize(29);
            } else if (type == ContextStrategyType.SLIDING_WINDOW || type == ContextStrategyType.FACTS) {
                assertThat(chat.messages()).hasSize(30);
                assertThat(chat.messages().getFirst().content()).isEqualTo("Цель: магазин, БД PostgreSQL");
                assertThat(chat.memory().discardedMessages()).isZero();
                assertThat(chat.memory().archivedUsage().calls()).isZero();
                assertThat(chat.messages().stream().filter(m -> m.metrics() != null)
                        .mapToInt(m -> m.metrics().totalTokens()).sum()).isEqualTo(450);
            } else {
                assertThat(chat.messages()).hasSize(10);
                assertThat(inputs.getLast().size()).isLessThanOrEqualTo(19);
            }
            if (type == ContextStrategyType.SLIDING_WINDOW || type == ContextStrategyType.FACTS) {
                assertThat(inputs.getLast()).hasSize(11);
                assertThat(inputs.getLast()).extracting(ChatMessage::content).doesNotContain("Цель: магазин, БД PostgreSQL");
            }
            if (type == ContextStrategyType.FACTS) {
                assertThat(extractions).hasValue(15);
                assertThat(chat.memory().extractionUsage().totalTokens()).isEqualTo(450);
                assertThat(chat.memory().extractionUsage().totalCostUsd()).isEqualByComparingTo("0.000540");
                assertThat(prompts.getLast()).contains("<facts>", "PostgreSQL");
            }
            if (type == ContextStrategyType.SUMMARY) assertThat(chat.summary().calls()).isEqualTo(2);
            int beforeRetry = inputs.size();
            service.send("architect", chat.id(), firstId, "Цель: магазин, БД PostgreSQL");
            assertThat(inputs).hasSize(beforeRetry);
            UUID chatId = chat.id();
            assertThatThrownBy(() -> service.send("architect", chatId, firstId, "изменено")).isInstanceOf(ChatFailure.class);
            assertThat(service(model).get("architect", chat.id())).isEqualTo(chat);
        }
    }

    @Test void factsUpdateReplaceDeleteAndRejectInvalidJsonWithoutCommittingATurn() throws Exception {
        var responses = new ArrayDeque<>(List.of(
                "{\"goal\":\"магазин\",\"budget\":\"100\"}",
                "{\"goal\":\"магазин\",\"budget\":\"200\"}",
                "{\"goal\":\"магазин\",\"budget\":\"\"}", "not JSON", "{\"goal\":\"магазин\"}"));
        AtomicInteger answers = new AtomicInteger();
        var service = service((d, messages) -> {
            if (extracting(d)) return reply(responses.remove());
            answers.incrementAndGet();
            return reply("Ответ");
        });
        var chat = service.create("architect", ContextStrategyType.FACTS);
        UUID id = chat.id();
        service.send("architect", id, UUID.randomUUID(), "Цель магазин, бюджет 100");
        var updated = service.send("architect", id, UUID.randomUUID(), "Бюджет теперь 200");
        assertThat(updated.memory().facts()).containsEntry("budget", "200");
        var before = service.send("architect", id, UUID.randomUUID(), "Забудь бюджет");
        assertThat(before.memory().facts()).doesNotContainKey("budget");
        UUID retry = UUID.randomUUID();
        assertThatThrownBy(() -> service.send("architect", id, retry, "Продолжим")).hasMessageContaining("JSON");
        assertThat(service.get("architect", id)).isEqualTo(before);
        assertThat(answers).hasValue(3);
        var events = service.stream("architect", id, retry, "Продолжим").collectList().block();
        assertThat(events).extracting(ChatService.StreamEvent::type).containsExactly(
                ChatService.StreamEvent.Type.STARTED, ChatService.StreamEvent.Type.UPDATING_FACTS, ChatService.StreamEvent.Type.COMPLETED);
        assertThat(service.get("architect", id).messages()).hasSize(8);
    }

    @Test void forkIsAtomicIdempotentIsolatedAndSupportsConcurrentIndependentContinuations() throws Exception {
        var contexts = new ConcurrentHashMap<String, List<String>>();
        var service = service((d, messages) -> {
            contexts.put(messages.getLast().content(), messages.stream().map(ChatMessage::content).toList());
            return reply("Ответ");
        });
        var root = service.create("architect", ContextStrategyType.BRANCHING);
        service.send("architect", root.id(), UUID.randomUUID(), "Общее ТЗ");
        var fork = service.fork("architect", root.id(), "Монолит", "Микросервисы");
        var a = fork.branches().getFirst();
        var b = fork.branches().getLast();
        assertThat(service.fork("architect", root.id(), "Повтор", "Повтор")).isEqualTo(fork);
        assertThat(a.checkpointId()).isEqualTo(b.checkpointId());
        assertThat(a.messages()).allMatch(m -> m.metrics() == null);
        assertThatThrownBy(() -> service.send("architect", root.id(), UUID.randomUUID(), "Нельзя"))
                .hasMessageContaining("checkpoint");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.send("architect", a.id(), UUID.randomUUID(), "Только A"));
            var second = executor.submit(() -> service.send("architect", b.id(), UUID.randomUUID(), "Только B"));
            first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
        }
        assertThat(contexts.get("Только A")).contains("Общее ТЗ").doesNotContain("Только B");
        assertThat(contexts.get("Только B")).contains("Общее ТЗ").doesNotContain("Только A");
        assertThat(service.get("architect", a.id()).messages()).hasSize(4);
        assertThat(service.get("architect", b.id()).messages()).hasSize(4);
        assertThat(service.get("architect", root.id()).messages()).hasSize(2);
        assertThat(service((d, messages) -> reply("Ответ")).list("architect")).hasSize(3);
        assertThatThrownBy(() -> service.get("chef", a.id())).isInstanceOf(ChatFailure.class);
        var nested = service.fork("architect", a.id(), "A1", "A2");
        assertThat(nested.branches()).hasSize(2);
        assertThat(service.list("architect")).hasSize(5);
    }

    @Test void invalidWindowSettingsAreRejected() {
        for (int size : new int[] {0, 3, 102}) {
            assertThatThrownBy(() -> new AgentDefinition.ContextManagement(ContextStrategyType.FACTS, size, 10, 1000, "facts"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void deletesSubtreesWithoutAffectingSiblingsAndRejectsDeletionDuringGeneration() throws Exception {
        var service = service((d, messages) -> reply("Ответ"));
        var root = service.create("architect", ContextStrategyType.BRANCHING);
        service.send("architect", root.id(), UUID.randomUUID(), "Общее ТЗ");
        var fork = service.fork("architect", root.id(), "A", "B");
        var a = fork.branches().getFirst();
        var b = fork.branches().getLast();
        service.fork("architect", a.id(), "A1", "A2");
        var unrelated = service.create("architect", ContextStrategyType.SLIDING_WINDOW);
        var inFlight = service.stream("architect", b.id(), UUID.randomUUID(), "В работе");
        assertThatThrownBy(() -> service.delete("architect", root.id())).hasMessageContaining("Дождитесь");
        assertThatThrownBy(() -> service.delete("chef", a.id())).isInstanceOf(ChatFailure.class);
        service.delete("architect", a.id());
        assertThat(service.list("architect")).extracting(Chat::id).containsExactlyInAnyOrder(root.id(), b.id(), unrelated.id());
        assertThat(service.get("architect", b.id())).isEqualTo(b);
        inFlight.take(1).blockLast();
        service.delete("architect", b.id());
        assertThat(service.get("architect", root.id()).readOnly()).isTrue();
        assertThatThrownBy(() -> service.send("architect", root.id(), UUID.randomUUID(), "Продолжим"))
                .hasMessageContaining("checkpoint");
        service.delete("architect", root.id());
        assertThat(service((d, messages) -> reply("Ответ")).list("architect"))
                .extracting(Chat::id).containsExactly(unrelated.id());
        assertThatThrownBy(() -> service.delete("architect", root.id())).isInstanceOf(ChatFailure.class);
    }

    @Test void slidingWindowRetainsLongTranscriptAcrossRestartAndFailedCalls() throws Exception {
        var contexts = new ArrayList<List<ChatMessage>>();
        ConversationModel model = (definition, messages) -> {
            contexts.add(messages);
            if (messages.getLast().content().equals("ошибка")) {
                throw new ChatFailure(ChatFailure.Kind.PROVIDER, "Тестовый сбой");
            }
            return reply("Ответ");
        };
        var service = service(model);
        var chat = service.create("architect", ContextStrategyType.SLIDING_WINDOW);
        UUID firstId = UUID.randomUUID();
        String firstText = "Шаг 0 " + "x".repeat(8000);
        for (int i = 0; i < 15; i++) {
            chat = service.send("architect", chat.id(), i == 0 ? firstId : UUID.randomUUID(),
                    i == 0 ? firstText : "Шаг " + i + " " + "x".repeat(8000));
        }
        assertThat(chat.messages()).hasSize(30);
        assertThat(chat.messages().stream().mapToInt(m -> m.content().length()).sum()).isGreaterThan(60000);
        assertThat(contexts.getLast()).hasSize(11);
        assertThat(contexts.getLast().getFirst().content()).startsWith("Шаг 9 ");
        var restarted = service(model);
        assertThat(restarted.get("architect", chat.id())).isEqualTo(chat);
        int callsBeforeRetry = contexts.size();
        restarted.send("architect", chat.id(), firstId, firstText);
        assertThat(contexts).hasSize(callsBeforeRetry);
        UUID chatId = chat.id();
        assertThatThrownBy(() -> restarted.send("architect", chatId, UUID.randomUUID(), "ошибка"))
                .hasMessageContaining("Тестовый сбой");
        assertThat(restarted.get("architect", chatId)).isEqualTo(chat);
        var continued = restarted.send("architect", chatId, UUID.randomUUID(), "Продолжим");
        assertThat(continued.messages()).hasSize(32);
        assertThat(continued.messages().getFirst().content()).isEqualTo(firstText);
        assertThat(contexts.getLast()).hasSize(11);
        assertThat(contexts.getLast().getFirst().content()).startsWith("Шаг 10 ");
    }

    @Test void factsRetainsLongTranscriptWhileBothProviderCallsUseOnlyTheWindow() throws Exception {
        var answerContexts = new ArrayList<List<ChatMessage>>();
        var extractionInputs = new ArrayList<String>();
        ConversationModel model = new ConversationModel() {
            @Override public Reply reply(AgentDefinition definition, List<ChatMessage> messages) {
                answerContexts.add(messages);
                return ContextStrategiesTest.reply("Ответ");
            }
            @Override public reactor.core.publisher.Flux<StreamPart> extractFacts(
                    AgentDefinition definition, List<ChatMessage> messages) {
                extractionInputs.add(messages.getFirst().content());
                return reactor.core.publisher.Flux.just(StreamPart.completed(
                        ContextStrategiesTest.reply("{\"goal\":\"магазин\",\"database\":\"PostgreSQL\"}")));
            }
        };
        var service = service(model);
        var chat = service.create("architect", ContextStrategyType.FACTS);
        UUID firstId = UUID.randomUUID();
        String firstText = "Шаг 0 " + "x".repeat(5000);
        for (int i = 0; i < 15; i++) {
            chat = service.send("architect", chat.id(), i == 0 ? firstId : UUID.randomUUID(),
                    i == 0 ? firstText : "Шаг " + i + " " + "x".repeat(5000));
        }

        assertThat(chat.messages()).hasSize(30);
        assertThat(chat.messages().stream().mapToInt(message -> message.content().length()).sum())
                .isGreaterThan(60000);
        assertThat(chat.messages().getFirst().content()).isEqualTo(firstText);
        assertThat(chat.memory().discardedMessages()).isZero();
        assertThat(chat.memory().archivedUsage().calls()).isZero();
        assertThat(chat.memory().facts()).containsEntry("database", "PostgreSQL");
        assertThat(chat.memory().extractionUsage().calls()).isEqualTo(15);
        assertThat(answerContexts.getLast()).hasSize(11);
        assertThat(answerContexts.getLast().getFirst().content()).startsWith("Шаг 9 ");
        assertThat(extractionInputs.getLast()).contains("Шаг 9 ", "Шаг 13 ", "Шаг 14 ")
                .doesNotContain("Шаг 0 ", "Шаг 8 ");

        var restarted = service(model);
        assertThat(restarted.get("architect", chat.id())).isEqualTo(chat);
        int callsBeforeRetry = answerContexts.size();
        int extractionsBeforeRetry = extractionInputs.size();
        restarted.send("architect", chat.id(), firstId, firstText);
        assertThat(answerContexts).hasSize(callsBeforeRetry);
        assertThat(extractionInputs).hasSize(extractionsBeforeRetry);
    }
}
