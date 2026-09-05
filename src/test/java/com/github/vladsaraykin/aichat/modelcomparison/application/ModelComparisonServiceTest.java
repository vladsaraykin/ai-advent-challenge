package com.github.vladsaraykin.aichat.modelcomparison.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.vladsaraykin.aichat.modelcomparison.domain.TokenUsage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModelComparisonServiceTest {
    private ModelComparisonService service;

    @AfterEach void close() { if (service != null) service.shutdown(); }

    @Test
    void callsThreeTiersWithSamePromptAndLimitStableOrderAndBoundedConcurrency() {
        List<GenerationCommand> calls = new CopyOnWriteArrayList<>();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        GenerationClient client = command -> {
            calls.add(command);
            int current = active.incrementAndGet(); peak.accumulateAndGet(current, Math::max);
            try { Thread.sleep(command.model().endsWith("luna") ? 60 : 10); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { active.decrementAndGet(); }
            return new ProviderGeneration("answer", new TokenUsage(10, 20, 30));
        };
        service = new ModelComparisonService(client, Executors.newFixedThreadPool(2));
        var result = service.compare("same prompt", 512,
                List.of("gpt-4o-mini", "gpt-5-mini", "gpt-5.6-sol"));

        assertThat(calls).extracting(GenerationCommand::prompt).containsOnly("same prompt");
        assertThat(calls).extracting(GenerationCommand::maxTokens).containsOnly(512);
        assertThat(calls).extracting(GenerationCommand::model).containsExactlyInAnyOrder(
                "gpt-4o-mini", "gpt-5-mini", "gpt-5.6-sol");
        assertThat(peak).hasValueLessThanOrEqualTo(2);
        assertThat(result.results()).extracting(item -> item.tier().level()).containsExactly("WEAK", "MEDIUM", "STRONG");
        assertThat(result.totalUsage()).isEqualTo(new TokenUsage(30, 60, 90));
        assertThat(result.estimatedTotalCostUsd()).isPositive();
    }

    @Test
    void preservesSuccessfulResultsAndSanitizesProviderFailure() {
        service = new ModelComparisonService(command -> {
            if (command.model().equals("gpt-5-mini")) throw new IllegalStateException("secret body");
            return new ProviderGeneration("ok", TokenUsage.EMPTY);
        }, Executors.newFixedThreadPool(2));
        var results = service.compare("prompt", 100,
                List.of("gpt-4o-mini", "gpt-5-mini", "gpt-5.6-sol")).results();
        assertThat(results).extracting(item -> item.status().name()).containsExactly("SUCCESS", "ERROR", "SUCCESS");
        assertThat(results.get(1).error()).doesNotContain("secret");
    }

    @Test
    void rejectsDuplicateAndUnknownModelsBeforeCallingProvider() {
        service = new ModelComparisonService(command -> {
            throw new AssertionError("provider must not be called");
        }, Executors.newFixedThreadPool(2));

        assertThatThrownBy(() -> service.compare("prompt", 100,
                List.of("gpt-5-mini", "gpt-5-mini", "gpt-5.6-sol")))
                .hasMessageContaining("три разные модели");
        assertThatThrownBy(() -> service.compare("prompt", 100,
                List.of("gpt-4o-mini", "invented-model", "gpt-5.6-sol")))
                .hasMessageContaining("Неизвестная модель");
    }

    @Test
    void reportsEmptyProviderAnswerAsVisibleFailure() {
        service = new ModelComparisonService(command -> new ProviderGeneration("  ", new TokenUsage(10, 100, 110)),
                Executors.newFixedThreadPool(2));

        var results = service.compare("prompt", 100,
                List.of("gpt-4o-mini", "gpt-5-mini", "gpt-5.6-sol")).results();

        assertThat(results).allSatisfy(result -> {
            assertThat(result.status().name()).isEqualTo("ERROR");
            assertThat(result.error()).contains("Увеличьте лимит токенов");
            assertThat(result.usage().totalTokens()).isEqualTo(110);
            assertThat(result.estimatedCostUsd()).isPositive();
        });
    }
}
