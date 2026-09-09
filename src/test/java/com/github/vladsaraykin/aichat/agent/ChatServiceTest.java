package com.github.vladsaraykin.aichat.agent;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.agent.infrastructure.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
}
