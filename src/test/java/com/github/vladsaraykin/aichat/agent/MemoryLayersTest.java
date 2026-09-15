package com.github.vladsaraykin.aichat.agent;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.agent.infrastructure.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

class MemoryLayersTest {
    @Test void assistantQuestionsAreSavedImmediatelyBlockAdvanceAndCanBeAnsweredNextTurn() throws Exception {
        var service = service((d, messages) -> reply(extraction(d) ? EXTRACT
                : "Нужно уточнить:\n1. Какой email-провайдер?\n2. Уточните способ получения уведомлений."),
                "{\"questions\":[\"Какой email-провайдер?\",\"Уточните способ получения уведомлений.\",\"Какой email-провайдер?\"]}");
        var chat = service.create("architect");
        UUID request = UUID.randomUUID();
        var events = service.stream("architect", chat.id(), request, "Проектируем сервис").collectList().block();
        assertThat(events).extracting(ChatService.StreamEvent::type).containsSubsequence(
                ChatService.StreamEvent.Type.UPDATING_MEMORY, ChatService.StreamEvent.Type.SYNCING_QUESTIONS,
                ChatService.StreamEvent.Type.COMPLETED);
        var saved = service.get("architect", chat.id());
        assertThat(saved.workingMemory().openQuestions()).containsExactly("Какой email-провайдер?", "Уточните способ получения уведомлений.");
        assertThat(saved.workingMemory().usage().calls()).isEqualTo(2);
        assertThat(saved.workingMemory().usage().totalTokens()).isEqualTo(60);
        assertThatThrownBy(() -> service.advanceTask("architect", chat.id(), saved.workingMemory().version())).hasMessageContaining("вопросы");
        assertThat(service.send("architect", chat.id(), request, "Проектируем сервис")).isEqualTo(saved);
        var continued = service((d, messages) -> reply(extraction(d) ? EXTRACT : "Требования уточнены."));
        assertThat(continued.get("architect", chat.id())).isEqualTo(saved);
        var answered = continued.send("architect", chat.id(), UUID.randomUUID(), "1. SMTP. 2. HTTP API.");
        assertThat(answered.workingMemory().openQuestions()).isEmpty();
        assertThat(continued.advanceTask("architect", chat.id(), answered.workingMemory().version()).workingMemory().stage())
                .isEqualTo(WorkingMemory.Stage.DESIGN);
    }
    @Test void failedQuestionSyncDoesNotSaveAnswerOrPreparedMemoryAndRetryIsSafe() throws Exception {
        for (String invalid : List.of("not JSON", "{\"questions\":[\"Придуманный вопрос?\"]}", "{\"questions\":[42]}")) {
            var service = service((d, messages) -> reply(extraction(d) ? EXTRACT : "Какой email-провайдер?"), invalid);
            var chat = service.create("architect");
            var id = UUID.randomUUID();
            assertThatThrownBy(() -> service.send("architect", chat.id(), id, "Проектируем сервис")).hasMessageContaining("Ход не сохранён");
            assertThat(service.get("architect", chat.id())).isEqualTo(chat);
            var retry = service((d, messages) -> reply(extraction(d) ? EXTRACT : "Какой email-провайдер?"),
                    "{\"questions\":[\"Какой email-провайдер?\"]}");
            var saved = retry.send("architect", chat.id(), id, "Проектируем сервис");
            assertThat(saved.messages()).hasSize(2);
            assertThat(saved.workingMemory().openQuestions()).containsExactly("Какой email-провайдер?");
            assertThat(saved.workingMemory().usage().calls()).isEqualTo(2);
        }
    }
    @Test void firstUserScenarioAcceptsFencedJsonAndWhitespaceOnlyQuoteDifferences() throws Exception {
        String prompt = "Проектируем сервис уведомлений для интернет-магазина. В этой задаче используем Java и PostgreSQL. "
                + "Пока отправляем только email. Нагрузка — 1000 уведомлений в минуту. Команда — два разработчика. В\n"
                + "целом я предпочитаю краткие ответы и минимум зависимостей.";
        String candidate = PROPOSAL.replace("Предпочитаю кратко", "В целом я предпочитаю краткие ответы и минимум зависимостей.");
        var service = service((d, messages) -> reply(extraction(d)
                ? "```json\n{\"task\":" + TASK + ",\"proposals\":[" + candidate + "]}\n```" : "Ответ"));
        var chat = service.create("architect");
        var result = service.send("architect", chat.id(), UUID.randomUUID(), prompt);
        assertThat(result.messages()).hasSize(2);
        assertThat(result.workingMemory().proposals().getFirst().evidence()).contains("В\nцелом");
        assertThat(service.memory("architect").entries()).isEmpty();
    }
    @TempDir Path directory;
    static final String TASK = """
            {"goal":"Сервис уведомлений","requirements":{"channel":"email"},"constraints":{"language":"Java"},"decisions":{},"openQuestions":[]}
            """.strip();
    static final String EXTRACT = "{\"task\":" + TASK + ",\"proposals\":[]}";
    static final String PROPOSAL = """
            {"scope":"GLOBAL","key":"style","value":"кратко","evidence":"Предпочитаю кратко"}
            """.strip();
    static ConversationModel.Reply reply(String text) {
        return new ConversationModel.Reply(text, new ChatMessage.Metrics("gpt-4.1-mini", 50,
                1, 2, 3, 10, 0, 20, 30, new BigDecimal("0.000004"), new BigDecimal("0.000032"), new BigDecimal("0.000036"), "stop"));
    }
    static boolean extraction(AgentDefinition d) { return d.systemPrompt().equals(d.memoryLayers().systemPrompt()); }
    ChatService service(ConversationModel model) throws Exception {
        return service(model, "{\"questions\":[]}");
    }
    ChatService service(ConversationModel model, String questions) throws Exception {
        ConversationModel wrapped = new ConversationModel() {
            public Reply reply(AgentDefinition d, List<ChatMessage> messages) { return model.reply(d, messages); }
            public reactor.core.publisher.Flux<StreamPart> stream(AgentDefinition d, ContextSummary summary, List<ChatMessage> messages) {
                return model.stream(d, summary, messages);
            }
            public reactor.core.publisher.Flux<StreamPart> extractMemory(AgentDefinition d, List<ChatMessage> messages) {
                return model.extractMemory(d, messages);
            }
            public reactor.core.publisher.Flux<StreamPart> extractQuestions(AgentDefinition d, List<ChatMessage> messages) {
                return reactor.core.publisher.Flux.just(StreamPart.completed(MemoryLayersTest.reply(questions)));
            }
            public Mono<Reply> summarize(AgentDefinition d, ContextSummary previous, List<ChatMessage> messages) {
                return model.summarize(d, previous, messages);
            }
        };
        return new ChatService(new AgentRegistry(wrapped, "classpath:agents/*.yaml"),
                new FileChatRepository(directory.resolve("chats").toString()),
                new FileLongTermMemoryRepository(directory.resolve("long-term").toString()));
    }
    @Test void allStrategiesKeepTaskSeparateAndUseTwoCallsWithoutDuplicateFactsExtraction() throws Exception {
        var prompts = new ArrayList<String>();
        var inputs = new ArrayList<List<ChatMessage>>();
        AtomicInteger extracts = new AtomicInteger();
        ConversationModel model = new ConversationModel() {
            public Reply reply(AgentDefinition d, List<ChatMessage> messages) {
                if (extraction(d)) { extracts.incrementAndGet(); return MemoryLayersTest.reply(EXTRACT); }
                assertThat(d.systemPrompt()).isNotEqualTo(d.contextManagement().factsPrompt());
                prompts.add(d.systemPrompt()); inputs.add(messages);
                return MemoryLayersTest.reply("Архитектура");
            }
            public Mono<Reply> summarize(AgentDefinition d, ContextSummary previous, List<ChatMessage> messages) {
                return Mono.just(MemoryLayersTest.reply("Сервис уведомлений"));
            }
        };
        var service = service(model);
        for (var strategy : ContextStrategyType.values()) {
            extracts.set(0); inputs.clear(); prompts.clear();
            Chat chat = service.create("architect", strategy);
            UUID first = UUID.randomUUID();
            for (int i = 0; i < 15; i++) chat = service.send("architect", chat.id(), i == 0 ? first : UUID.randomUUID(), "Запрос " + i);
            assertThat(extracts).hasValue(15);
            assertThat(inputs).hasSize(15);
            assertThat(chat.workingMemory().goal()).isEqualTo("Сервис уведомлений");
            assertThat(chat.workingMemory().usage().calls()).isEqualTo(30);
            assertThat(chat.workingMemory().usage().totalTokens()).isEqualTo(900);
            assertThat(chat.memory().extractionUsage().calls()).isZero();
            assertThat(prompts.getLast()).contains("Рабочая память JSON", "email", "Java", "REQUIREMENTS");
            if (strategy == ContextStrategyType.SLIDING_WINDOW || strategy == ContextStrategyType.FACTS) {
                assertThat(chat.messages()).hasSize(30);
                assertThat(inputs.getLast()).hasSize(11);
            }
            assertThat(service.send("architect", chat.id(), first, "Запрос 0")).isEqualTo(chat);
            assertThat(extracts).hasValue(15);
            assertThat(service(model).get("architect", chat.id())).isEqualTo(chat);
        }
    }
    @Test void confirmationTransfersOnlyExplicitMemoryAcrossChatsAndDeletionDoesNotResurrectIt() throws Exception {
        var prompts = new ArrayList<String>();
        var service = service((d, messages) -> {
            if (extraction(d)) return reply(messages.getLast().content().endsWith("Предпочитаю кратко")
                    ? "{\"task\":" + TASK + ",\"proposals\":[" + PROPOSAL + "]}" : EXTRACT);
            prompts.add(d.systemPrompt()); return reply("Ответ");
        });
        var chat = service.create("architect");
        var turn = service.send("architect", chat.id(), UUID.randomUUID(), "Предпочитаю кратко");
        var proposal = turn.workingMemory().proposals().getFirst();
        assertThat(service.memory("architect").entries()).isEmpty();
        var saved = service.acceptProposal("architect", chat.id(), 0, turn.workingMemory().version(), proposal.id());
        assertThat(saved.entries()).hasSize(1);
        assertThat(service.acceptProposal("architect", chat.id(), 0, turn.workingMemory().version(), proposal.id())).isEqualTo(saved);
        var second = service.create("architect");
        assertThat(second.workingMemory().goal()).isEmpty();
        service.send("architect", second.id(), UUID.randomUUID(), "Новый запрос");
        assertThat(prompts.getLast()).contains("style", "кратко");
        service.delete("architect", chat.id());
        assertThat(service.memory("architect")).isEqualTo(saved);
        var restarted = service((d, messages) -> reply(EXTRACT));
        assertThat(restarted.memory("architect")).isEqualTo(saved);
        var removed = service.deleteMemory("architect", saved.version(), proposal.id());
        assertThat(removed.entries()).isEmpty();
        assertThat(removed.resolvedProposals()).contains(proposal.id());
        assertThatThrownBy(() -> service.memory("chef")).hasMessageContaining("не включены");
        assertThatThrownBy(() -> service.editTask("chef", second.id(), 0, "", new MemoryService.TaskData("", Map.of(), Map.of(), Map.of(), List.of())))
                .isInstanceOf(ChatFailure.class);
    }
    @Test void projectScopedSelectionAndOptimisticWritesAreIsolated() throws Exception {
        var prompts = new ArrayList<String>();
        var service = service((d, messages) -> {
            if (extraction(d)) return reply(EXTRACT);
            prompts.add(d.systemPrompt()); return reply("ok");
        });
        UUID entryId = UUID.randomUUID();
        service.putMemory("architect", 0, entryId, WorkingMemory.Scope.PROJECT, "project-a", "db", "SECRET_PROJECT_DATABASE");
        assertThatThrownBy(() -> service.putMemory("architect", 0, UUID.randomUUID(), WorkingMemory.Scope.GLOBAL, "", "x", "y"))
                .hasMessageContaining("изменилась");
        var chat = service.create("architect");
        chat = service.send("architect", chat.id(), UUID.randomUUID(), "Запрос");
        assertThat(prompts.getLast()).doesNotContain("SECRET_PROJECT_DATABASE");
        chat = service.editTask("architect", chat.id(), chat.workingMemory().version(), "project-a",
                new MemoryService.TaskData("Сервис уведомлений", Map.of("channel", "email"), Map.of("language", "Java"), Map.of(), List.of()));
        service.send("architect", chat.id(), UUID.randomUUID(), "Уточнение");
        assertThat(prompts.getLast()).contains("SECRET_PROJECT_DATABASE");
        var repo = new FileLongTermMemoryRepository(directory.resolve("long-term").toString());
        assertThat(repo.get("chef").entries()).isEmpty();
        assertThat(repo.get("architect").entries()).hasSize(1);
        var success = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(pool.submit(() -> concurrentPut(service, success)), pool.submit(() -> concurrentPut(service, success)));
            for (var task : tasks) task.get();
        }
        assertThat(success).hasValue(1);
    }
    private void concurrentPut(ChatService service, AtomicInteger success) {
        try { service.putMemory("architect", 1, UUID.randomUUID(), WorkingMemory.Scope.GLOBAL, "", UUID.randomUUID().toString(), "value"); success.incrementAndGet(); }
        catch (ChatFailure e) { assertThat(e.kind()).isEqualTo(ChatFailure.Kind.BUSY); }
    }
    @Test void failedExtractionOrAnswerDoesNotCommitAndRetryDoesNotDuplicate() throws Exception {
        var output = new AtomicReference<>(EXTRACT);
        var failAnswer = new AtomicBoolean(false);
        var service = service((d, messages) -> {
            if (extraction(d)) return reply(output.get());
            if (failAnswer.get()) throw new ChatFailure(ChatFailure.Kind.PROVIDER, "provider failed");
            return reply("ok");
        });
        var chat = service.create("architect");
        UUID messageId = UUID.randomUUID();
        for (String invalid : List.of("not JSON", "{}", EXTRACT.replace("\"email\"", "42"),
                "{\"task\":" + TASK + ",\"proposals\":[" + PROPOSAL + "]}")) {
            output.set(invalid);
            assertThatThrownBy(() -> service.send("architect", chat.id(), messageId, "Не содержит цитату")).hasMessageContaining("JSON");
            assertThat(service.get("architect", chat.id())).isEqualTo(chat);
        }
        output.set(EXTRACT); failAnswer.set(true);
        assertThatThrownBy(() -> service.send("architect", chat.id(), messageId, "Не содержит цитату")).hasMessageContaining("provider failed");
        assertThat(service.get("architect", chat.id())).isEqualTo(chat);
        failAnswer.set(false);
        var events = service.stream("architect", chat.id(), messageId, "Не содержит цитату").collectList().block();
        assertThat(events).extracting(ChatService.StreamEvent::type).contains(ChatService.StreamEvent.Type.UPDATING_MEMORY);
        var completed = service.get("architect", chat.id());
        assertThat(completed.messages()).hasSize(2);
        assertThat(completed.workingMemory().usage().calls()).isEqualTo(2);
        assertThat(service.send("architect", chat.id(), messageId, "Не содержит цитату")).isEqualTo(completed);
    }
    @Test void stateTransitionsRequireExplicitCommandsAndBranchesKeepIndependentTaskState() throws Exception {
        var service = service((d, messages) -> reply(extraction(d) ? EXTRACT : "ok"));
        var root = service.create("architect", ContextStrategyType.BRANCHING);
        UUID id = root.id();
        assertThatThrownBy(() -> service.advanceTask("architect", id, 0)).hasMessageContaining("Заполните");
        root = service.send("architect", id, UUID.randomUUID(), "Подтверждаю требования");
        assertThat(root.workingMemory().stage()).isEqualTo(WorkingMemory.Stage.REQUIREMENTS);
        root = service.advanceTask("architect", id, root.workingMemory().version());
        assertThat(root.workingMemory().stage()).isEqualTo(WorkingMemory.Stage.DESIGN);
        long version = root.workingMemory().version();
        assertThatThrownBy(() -> service.advanceTask("architect", id, version)).hasMessageContaining("решения");
        root = service.editTask("architect", id, version, "", new MemoryService.TaskData("Сервис уведомлений", Map.of("channel", "email"),
                Map.of("language", "Java"), Map.of("architecture", "monolith"), List.of()));
        root = service.advanceTask("architect", id, root.workingMemory().version());
        root = service.advanceTask("architect", id, root.workingMemory().version());
        assertThat(root.workingMemory().stage()).isEqualTo(WorkingMemory.Stage.DONE);
        var fork = service.fork("architect", id, "A", "B");
        var a = fork.branches().getFirst(); var b = fork.branches().getLast();
        assertThat(a.workingMemory().goal()).isEqualTo(root.workingMemory().goal());
        assertThat(a.workingMemory().usage().calls()).isZero();
        var changed = service.editTask("architect", a.id(), 0, "", new MemoryService.TaskData("Другая задача", Map.of("channel", "sms"), Map.of(), Map.of(), List.of()));
        assertThat(changed.workingMemory().stage()).isEqualTo(WorkingMemory.Stage.REQUIREMENTS);
        assertThat(service.get("architect", b.id()).workingMemory().stage()).isEqualTo(WorkingMemory.Stage.DONE);
        assertThatThrownBy(() -> service.advanceTask("architect", id, 0)).hasMessageContaining("Checkpoint");
        var inFlight = service.stream("architect", a.id(), UUID.randomUUID(), "Дальше");
        assertThatThrownBy(() -> service.advanceTask("architect", a.id(), changed.workingMemory().version())).hasMessageContaining("ожидается");
        inFlight.collectList().block();
    }
    @Test void incompleteExtractionAndCancelledAnswerPreserveTaskAndReleaseChatLock() throws Exception {
        var incomplete = new AtomicBoolean(true);
        var cancelAnswer = new AtomicBoolean(false);
        ConversationModel model = new ConversationModel() {
            public Reply reply(AgentDefinition d, List<ChatMessage> messages) { return MemoryLayersTest.reply(EXTRACT); }
            public reactor.core.publisher.Flux<StreamPart> extractMemory(AgentDefinition d, List<ChatMessage> messages) {
                if (incomplete.get()) {
                    var m = MemoryLayersTest.reply(EXTRACT).metrics();
                    var truncated = new ChatMessage.Metrics(m.model(), m.durationMs(), m.currentMessageTokens(), m.historyTokens(),
                            m.systemPromptTokens(), m.promptTokens(), m.cachedPromptTokens(), m.completionTokens(), m.totalTokens(),
                            m.inputCostUsd(), m.outputCostUsd(), m.totalCostUsd(), "length");
                    return reactor.core.publisher.Flux.just(StreamPart.completed(new Reply(EXTRACT, truncated)));
                }
                return reactor.core.publisher.Flux.just(StreamPart.completed(MemoryLayersTest.reply(EXTRACT)));
            }
            public reactor.core.publisher.Flux<StreamPart> stream(AgentDefinition d, ContextSummary summary, List<ChatMessage> messages) {
                return cancelAnswer.get() ? reactor.core.publisher.Flux.concat(reactor.core.publisher.Flux.just(StreamPart.delta("частичный")),
                        reactor.core.publisher.Flux.never()) : reactor.core.publisher.Flux.just(StreamPart.completed(MemoryLayersTest.reply("ok")));
            }
        };
        var service = service(model);
        var chat = service.create("architect");
        UUID request = UUID.randomUUID();
        assertThatThrownBy(() -> service.send("architect", chat.id(), request, "Запрос")).hasMessageContaining("JSON");
        assertThat(service.get("architect", chat.id())).isEqualTo(chat);
        incomplete.set(false); cancelAnswer.set(true);
        service.stream("architect", chat.id(), request, "Запрос").takeUntil(e -> e.type() == ChatService.StreamEvent.Type.DELTA).collectList().block();
        assertThat(service.get("architect", chat.id())).isEqualTo(chat);
        cancelAnswer.set(false);
        assertThat(service.send("architect", chat.id(), request, "Запрос").messages()).hasSize(2);
    }
}
