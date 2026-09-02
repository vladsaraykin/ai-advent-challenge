package com.github.vladsaraykin.aichat.reasoning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class ReasoningComparisonServiceTest {

    @Test
    void usesCompletionTokenLimitForGpt5ReasoningModels() {
        OpenAiChatOptions options = ReasoningComparisonService.optionsFor("gpt-5.6-sol", 2_000);

        assertThat(options.getMaxCompletionTokens()).isEqualTo(2_000);
        assertThat(options.getMaxTokens()).isNull();
        assertThat(options.getTemperature()).isNull();
    }

    @Test
    void keepsLegacyTokenLimitAndStableTemperatureForGpt4Models() {
        OpenAiChatOptions options = ReasoningComparisonService.optionsFor("gpt-4.1-mini", 2_000);

        assertThat(options.getMaxTokens()).isEqualTo(2_000);
        assertThat(options.getMaxCompletionTokens()).isNull();
        assertThat(options.getTemperature()).isEqualTo(0.2);
    }

    @Test
    void comparesFourApproachesWithAtMostTwoConcurrentCallsAndAggregatesTokens() {
        ChatModel model = mock(ChatModel.class);
        AtomicInteger activeCalls = new AtomicInteger();
        AtomicInteger maximumActiveCalls = new AtomicInteger();
        CountDownLatch firstPairStarted = new CountDownLatch(2);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            int active = activeCalls.incrementAndGet();
            maximumActiveCalls.accumulateAndGet(active, Math::max);
            firstPairStarted.countDown();
            try {
                assertThat(firstPairStarted.await(2, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(20);
                return response(answerFor(prompt));
            } finally {
                activeCalls.decrementAndGet();
            }
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ReasoningComparisonService service = new ReasoningComparisonService(model, executor);
        ReasoningComparisonForm form = new ReasoningComparisonForm();
        form.setTask("Сколько будет 2 + 2?");
        form.setModel("test-model");
        form.setMaxTokens(512);

        ReasoningComparison comparison = service.compare(form);

        assertThat(maximumActiveCalls).hasValue(2);
        assertThat(comparison.results()).extracting(ApproachResult::approach).containsExactly(
                ReasoningApproach.DIRECT,
                ReasoningApproach.STEP_BY_STEP,
                ReasoningApproach.GENERATED_PROMPT,
                ReasoningApproach.EXPERT_PANEL);
        assertThat(comparison.results()).extracting(ApproachResult::answer).containsExactly(
                "direct answer", "step answer", "generated answer", "expert answer");
        assertThat(comparison.results().get(2).turns()).hasSize(4);
        assertThat(comparison.results().get(2).usage()).isEqualTo(new TokenUsage(2, 4, 6));
        assertThat(comparison.summary()).isEqualTo("comparison summary");
        assertThat(comparison.usage()).isEqualTo(new TokenUsage(6, 12, 18));
        assertThat(comparison.durationMillis()).isNotNegative();
        assertThat(comparison.results()).allSatisfy(result -> {
            assertThat(result.durationMillis()).isNotNegative();
            assertThat(result.durationLabel()).isNotBlank();
        });
        assertThat(comparison.markdown()).contains(
                "# Сравнение способов рассуждения",
                "## Прямой ответ",
                "## Пошаговое решение",
                "## Сначала создать промпт",
                "## Группа экспертов",
                "Всего токенов (включая сравнительный анализ):** 18",
                "**Общее время:**");

        service.shutdown();
        assertThat(executor.isShutdown()).isTrue();
    }

    private static String answerFor(Prompt prompt) {
        String text = prompt.getInstructions().getLast().getText();
        if (text.startsWith("Сравни четыре решения")) {
            return "comparison summary";
        }
        if (text.startsWith("Составь наилучший")) {
            return "generated solver prompt";
        }
        if (text.equals("generated solver prompt")) {
            return "generated answer";
        }
        if (text.startsWith("Решите задачу группой экспертов")) {
            return "expert answer";
        }
        if (text.contains("Решай пошагово")) {
            return "step answer";
        }
        return "direct answer";
    }

    private static ChatResponse response(String answer) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(1, 2, 3))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))), metadata);
    }
}
