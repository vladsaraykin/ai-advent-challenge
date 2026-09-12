package com.github.vladsaraykin.aichat.agent;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.agent.infrastructure.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

class ChatServiceTest {
    @TempDir Path directory;
    private record Call(AgentDefinition definition, List<ChatMessage> history) { }
    private static ConversationModel.Reply reply() {
        return new ConversationModel.Reply("Ответ", new ChatMessage.Metrics("gpt-4.1-mini", 42,
                3, 7, 5, 10, 0, 20, 30,
                new BigDecimal("0.00000400"), new BigDecimal("0.00003200"),
                new BigDecimal("0.00003600"), "stop"));
    }
    private ChatService service(ConversationModel model) throws Exception {
        return new ChatService(new AgentRegistry(model, "classpath:agents/*.yaml"), new FileChatRepository(directory.toString()));
    }

    @Test void isolatesAgentsAndChatsAndRestoresHistoryAfterRestart() throws Exception {
        List<Call> calls = new ArrayList<>();
        var service = service((definition, messages) -> { calls.add(new Call(definition, messages)); return reply(); });
        Chat first = service.create("architect");
        Chat second = service.create("architect");
        Chat chef = service.create("chef");
        service.send("architect", first.id(), UUID.randomUUID(), "Мой проект — магазин");
        service.send("architect", second.id(), UUID.randomUUID(), "Другая система");
        service.send("chef", chef.id(), UUID.randomUUID(), "Есть картофель");

        var restarted = service((definition, messages) -> { calls.add(new Call(definition, messages)); return reply(); });
        Chat continued = restarted.send("architect", first.id(), UUID.randomUUID(), "Как хранить товары?");
        assertThat(calls.get(3).history()).extracting(ChatMessage::content)
                .containsExactly("Мой проект — магазин", "Ответ", "Как хранить товары?");
        assertThat(calls.get(3).history()).extracting(ChatMessage::role)
                .containsExactly(ChatMessage.Role.USER, ChatMessage.Role.ASSISTANT, ChatMessage.Role.USER);
        assertThat(calls.get(2).definition().id()).isEqualTo("chef");
        assertThat(calls.get(2).history()).hasSize(1);
        assertThat(continued.messages()).hasSize(4);
        assertThat(continued.messages().getLast().metrics().totalTokens()).isEqualTo(30);
        assertThat(restarted.list("architect")).hasSize(2);
        assertThat(restarted.list("chef")).hasSize(1);
        assertThatThrownBy(() -> restarted.get("chef", first.id())).isInstanceOf(ChatFailure.class);
    }

    @Test void repeatedMessageDoesNotCallProviderAgainAndFailureDoesNotCorruptHistory() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        var service = service((definition, messages) -> {
            requests.incrementAndGet();
            if (messages.getLast().content().equals("ошибка")) throw new ChatFailure(ChatFailure.Kind.PROVIDER, "Сбой");
            return reply();
        });
        var chat = service.create("architect");
        UUID id = UUID.randomUUID();
        service.send("architect", chat.id(), id, "Привет");
        service.send("architect", chat.id(), id, "Привет");
        assertThat(requests).hasValue(1);
        assertThatThrownBy(() -> service.send("architect", chat.id(), id, "Изменённый текст")).isInstanceOf(ChatFailure.class);
        assertThatThrownBy(() -> service.send("architect", chat.id(), UUID.randomUUID(), "ошибка")).isInstanceOf(ChatFailure.class);
        assertThat(service.get("architect", chat.id()).messages()).hasSize(2);
        service.send("architect", chat.id(), UUID.randomUUID(), "Ещё вопрос");
        assertThat(service.get("architect", chat.id()).messages()).hasSize(4);
    }

    @Test void rejectsConcurrentSendWithinSameChatAndAllowsOtherChats() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var service = service((definition, messages) -> {
            if (messages.getLast().content().equals("долго")) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }
            return reply();
        });
        Chat first = service.create("architect");
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Chat> result = executor.submit(() -> service.send("architect", first.id(), UUID.randomUUID(), "долго"));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.send("architect", first.id(), UUID.randomUUID(), "второй"))
                        .isInstanceOf(ChatFailure.class).hasMessageContaining("уже ожидается ответ");
                Chat other = service.create("chef");
                assertThat(service.send("chef", other.id(), UUID.randomUUID(), "быстро").messages()).hasSize(2);
            } finally { release.countDown(); }
            assertThat(result.get(2, TimeUnit.SECONDS).messages()).hasSize(2);
        }
    }

    @Test void contextLimitDoesNotSilentlyDropEarlierHistory() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        var definition = new AgentDefinition("test", "Test", "Description", "gpt-4.1-mini", "System",
                100, null, 30, 1000,
                new TokenPricing(new BigDecimal("0.40"), new BigDecimal("0.10"), new BigDecimal("1.60")));
        var agent = new ConfiguredAgent(definition, (config, messages) -> { requests.incrementAndGet(); return reply(); });
        var user = new ChatMessage(UUID.randomUUID(), ChatMessage.Role.USER, "x".repeat(1001), java.time.Instant.now(), null);
        assertThatThrownBy(() -> agent.answer(List.of(), user)).hasMessageContaining("лимит контекста");
        assertThat(requests).hasValue(0);
    }

    @Test void boundsSimultaneousProviderCallsAcrossAgents() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        var service = service((definition, messages) -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
            catch (InterruptedException exception) { throw new RuntimeException(exception); }
            return reply();
        });
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<Chat>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Chat chat = service.create(i % 2 == 0 ? "architect" : "chef");
                futures.add(executor.submit(() -> service.send(chat.agentId(), chat.id(), UUID.randomUUID(), "вопрос")));
            }
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                Chat fifth = service.create("chef");
                assertThatThrownBy(() -> service.send("chef", fifth.id(), UUID.randomUUID(), "ещё"))
                        .isInstanceOf(ChatFailure.class).hasMessageContaining("Агенты заняты");
            } finally { release.countDown(); }
            for (var future : futures) assertThat(future.get(2, TimeUnit.SECONDS).messages()).hasSize(2);
        }
    }

    @Test void corruptedStorageIsReportedAndNotOverwritten() throws Exception {
        var repository = new FileChatRepository(directory.toString());
        var chat = Chat.create("architect");
        repository.save(chat);
        Path file = directory.resolve("architect").resolve(chat.id() + ".json");
        Files.writeString(file, "invalid json");
        assertThatThrownBy(() -> repository.get("architect", chat.id())).isInstanceOf(ChatFailure.class);
        assertThat(Files.readString(file)).isEqualTo("invalid json");
    }

    @Test void readsLegacyMetricsWithoutInventingHistoricalCost() throws Exception {
        UUID chatId = UUID.randomUUID();
        Path agentDirectory = Files.createDirectories(directory.resolve("architect"));
        Files.writeString(agentDirectory.resolve(chatId + ".json"), """
                {"id":"%s","agentId":"architect","title":"Legacy",
                 "createdAt":"2026-09-07T20:17:25Z","updatedAt":"2026-09-07T20:17:30Z",
                 "messages":[{"id":"%s","role":"ASSISTANT","content":"Old answer",
                 "createdAt":"2026-09-07T20:17:30Z","metrics":{"model":"gpt-4.1-mini",
                 "durationMs":100,"promptTokens":10,"completionTokens":5,"totalTokens":15,"finishReason":"stop"}}]}
                """.formatted(chatId, UUID.randomUUID()));
        Chat loaded = new FileChatRepository(directory.toString()).get("architect", chatId);
        assertThat(loaded.messages().getFirst().metrics().totalTokens()).isEqualTo(15);
        assertThat(loaded.messages().getFirst().metrics().totalCostUsd()).isNull();
    }

    @Test void streamsDeltasAndPersistsOnlyTheCompletedTurn() throws Exception {
        ConversationModel model = new ConversationModel() {
            @Override public Reply reply(AgentDefinition definition, List<ChatMessage> messages) {
                return ChatServiceTest.reply();
            }
            @Override public Flux<StreamPart> stream(AgentDefinition definition, List<ChatMessage> messages) {
                return Flux.just(StreamPart.delta("Часть "), StreamPart.delta("ответа"),
                        StreamPart.completed(new Reply("Часть ответа", ChatServiceTest.reply().metrics())));
            }
        };
        var service = service(model);
        Chat chat = service.create("architect");

        var events = service.stream("architect", chat.id(), UUID.randomUUID(), "Вопрос")
                .collectList().block(Duration.ofSeconds(2));

        assertThat(events).extracting(ChatService.StreamEvent::type).containsExactly(
                ChatService.StreamEvent.Type.STARTED, ChatService.StreamEvent.Type.DELTA,
                ChatService.StreamEvent.Type.DELTA, ChatService.StreamEvent.Type.COMPLETED);
        assertThat(events).extracting(ChatService.StreamEvent::text).containsExactly(null, "Часть ", "ответа", null);
        assertThat(service.get("architect", chat.id()).messages()).extracting(ChatMessage::content)
                .containsExactly("Вопрос", "Часть ответа");
    }

    @Test void failedOrCancelledStreamDoesNotPersistPartialTurnAndCanBeRetried() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ConversationModel model = new ConversationModel() {
            @Override public Reply reply(AgentDefinition definition, List<ChatMessage> messages) {
                return ChatServiceTest.reply();
            }
            @Override public Flux<StreamPart> stream(AgentDefinition definition, List<ChatMessage> messages) {
                if (attempts.incrementAndGet() == 1) return Flux.concat(Flux.just(StreamPart.delta("часть")),
                        Flux.error(new ChatFailure(ChatFailure.Kind.PROVIDER, "Сбой")));
                return Flux.just(StreamPart.delta("Ответ"),
                        StreamPart.completed(new Reply("Ответ", ChatServiceTest.reply().metrics())));
            }
        };
        var service = service(model);
        Chat chat = service.create("architect");
        UUID messageId = UUID.randomUUID();

        assertThatThrownBy(() -> service.stream("architect", chat.id(), messageId, "Вопрос")
                .collectList().block(Duration.ofSeconds(2))).hasMessageContaining("Сбой");
        assertThat(service.get("architect", chat.id()).messages()).isEmpty();
        service.stream("architect", chat.id(), messageId, "Вопрос").collectList().block(Duration.ofSeconds(2));
        assertThat(service.get("architect", chat.id()).messages()).hasSize(2);

        Chat cancelled = service.create("chef");
        service.stream("chef", cancelled.id(), UUID.randomUUID(), "Отмена").take(1).blockLast();
        assertThat(service.get("chef", cancelled.id()).messages()).isEmpty();
    }

    @Test void compressesOldMessagesUpdatesSummaryAndSendsOnlySummaryWithRecentHistory() throws Exception {
        List<ContextSummary> answerSummaries = new ArrayList<>();
        List<List<String>> answerContexts = new ArrayList<>();
        List<ContextSummary> previousSummaries = new ArrayList<>();
        AtomicInteger summaryCalls = new AtomicInteger();
        ConversationModel model = new ConversationModel() {
            @Override public Reply reply(AgentDefinition definition, List<ChatMessage> messages) {
                return ChatServiceTest.reply();
            }
            @Override public Flux<StreamPart> stream(AgentDefinition definition, ContextSummary summary,
                                                     List<ChatMessage> messages) {
                answerSummaries.add(summary);
                answerContexts.add(messages.stream().map(ChatMessage::content).toList());
                return Flux.just(StreamPart.delta("Ответ"), StreamPart.completed(ChatServiceTest.reply()));
            }
            @Override public Mono<Reply> summarize(AgentDefinition definition, ContextSummary previous,
                                                   List<ChatMessage> messages) {
                previousSummaries.add(previous);
                int call = summaryCalls.incrementAndGet();
                return Mono.just(new Reply("summary-" + call + ":" + messages.getFirst().content(),
                        ChatServiceTest.reply().metrics()));
            }
        };
        var service = service(model);
        Chat chat = service.create("architect");
        UUID firstMessageId = UUID.randomUUID();

        List<ChatService.StreamEvent> tenthEvents = null;
        for (int turn = 1; turn <= 15; turn++) {
            UUID messageId = turn == 1 ? firstMessageId : UUID.randomUUID();
            var events = service.stream("architect", chat.id(), messageId, "Вопрос " + turn)
                    .collectList().block(Duration.ofSeconds(2));
            if (turn == 10) tenthEvents = events;
        }

        assertThat(tenthEvents).extracting(ChatService.StreamEvent::type).containsExactly(
                ChatService.StreamEvent.Type.STARTED, ChatService.StreamEvent.Type.DELTA,
                ChatService.StreamEvent.Type.SUMMARIZING, ChatService.StreamEvent.Type.COMPLETED);
        Chat compressed = service.get("architect", chat.id());
        assertThat(compressed.messages()).hasSize(10);
        assertThat(compressed.messages()).extracting(ChatMessage::content)
                .containsExactly("Вопрос 11", "Ответ", "Вопрос 12", "Ответ", "Вопрос 13", "Ответ",
                        "Вопрос 14", "Ответ", "Вопрос 15", "Ответ");
        assertThat(compressed.summary().content()).startsWith("summary-2:");
        assertThat(compressed.summary().summarizedMessages()).isEqualTo(20);
        assertThat(compressed.summary().calls()).isEqualTo(2);
        assertThat(compressed.summary().totalTokens()).isEqualTo(60);
        assertThat(compressed.summary().totalCostUsd()).isEqualByComparingTo("0.00007200");
        assertThat(compressed.summary().archivedUsage().calls()).isEqualTo(10);
        assertThat(compressed.summary().archivedUsage().totalTokens()).isEqualTo(300);
        assertThat(compressed.summary().archivedUsage().totalCostUsd()).isEqualByComparingTo("0.00036000");
        assertThat(previousSummaries).hasSize(2);
        assertThat(previousSummaries.getFirst()).isNull();
        assertThat(previousSummaries.getLast().content()).startsWith("summary-1:");
        assertThat(answerSummaries.get(10).content()).startsWith("summary-1:");
        assertThat(answerContexts.get(10)).containsExactly("Вопрос 6", "Ответ", "Вопрос 7", "Ответ",
                "Вопрос 8", "Ответ", "Вопрос 9", "Ответ", "Вопрос 10", "Ответ", "Вопрос 11");

        var duplicate = service.stream("architect", chat.id(), firstMessageId, "Вопрос 1")
                .collectList().block(Duration.ofSeconds(2));
        assertThat(duplicate).extracting(ChatService.StreamEvent::type)
                .containsExactly(ChatService.StreamEvent.Type.COMPLETED);
        assertThat(summaryCalls).hasValue(2);
        assertThatThrownBy(() -> service.stream("architect", chat.id(), firstMessageId, "Другой текст"))
                .isInstanceOf(ChatFailure.class).hasMessageContaining("уже использован");

        var restarted = service(model);
        assertThat(restarted.get("architect", chat.id()).summary()).isEqualTo(compressed.summary());
    }

    @Test void compressesContextForTheNonStreamingEndpointToo() throws Exception {
        AtomicInteger summaryCalls = new AtomicInteger();
        ConversationModel model = new ConversationModel() {
            @Override public Reply reply(AgentDefinition definition, List<ChatMessage> messages) {
                return ChatServiceTest.reply();
            }
            @Override public Mono<Reply> summarize(AgentDefinition definition, ContextSummary previous,
                                                   List<ChatMessage> messages) {
                summaryCalls.incrementAndGet();
                return Mono.just(new Reply("Краткая память", ChatServiceTest.reply().metrics()));
            }
        };
        var service = service(model);
        Chat chat = service.create("chef");
        for (int turn = 1; turn <= 10; turn++) {
            service.send("chef", chat.id(), UUID.randomUUID(), "Вопрос " + turn);
        }

        Chat compressed = service.get("chef", chat.id());
        assertThat(compressed.summary().summarizedMessages()).isEqualTo(10);
        assertThat(compressed.messages()).hasSize(10);
        assertThat(compressed.messageCount()).isEqualTo(20);
        assertThat(summaryCalls).hasValue(1);
    }

    @Test void summaryFailureKeepsEveryMessageAndReturnsAWarning() throws Exception {
        AtomicInteger summaryCalls = new AtomicInteger();
        ConversationModel model = new ConversationModel() {
            @Override public Reply reply(AgentDefinition definition, List<ChatMessage> messages) {
                return ChatServiceTest.reply();
            }
            @Override public Mono<Reply> summarize(AgentDefinition definition, ContextSummary previous,
                                                   List<ChatMessage> messages) {
                summaryCalls.incrementAndGet();
                return Mono.error(new ChatFailure(ChatFailure.Kind.PROVIDER, "Summary unavailable"));
            }
        };
        var service = service(model);
        Chat chat = service.create("chef");
        List<ChatService.StreamEvent> events = null;
        for (int turn = 1; turn <= 10; turn++) {
            events = service.stream("chef", chat.id(), UUID.randomUUID(), "Вопрос " + turn)
                    .collectList().block(Duration.ofSeconds(2));
        }

        assertThat(events.getLast().type()).isEqualTo(ChatService.StreamEvent.Type.COMPLETED);
        assertThat(events.getLast().warning()).contains("сжатие истории не выполнено");
        assertThat(service.get("chef", chat.id()).summary()).isNull();
        assertThat(service.get("chef", chat.id()).messages()).hasSize(20);
        assertThat(summaryCalls).hasValue(1);

        var next = service.stream("chef", chat.id(), UUID.randomUUID(), "Вопрос 11")
                .collectList().block(Duration.ofSeconds(2));
        assertThat(next.getLast().warning()).contains("сообщения сохранены");
        assertThat(service.get("chef", chat.id()).messages()).hasSize(22);
        assertThat(summaryCalls).hasValue(2);
    }
}
