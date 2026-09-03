package com.github.vladsaraykin.aichat.temperature.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.vladsaraykin.aichat.temperature.domain.TokenUsage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TemperatureExperimentServiceTest {
    private TemperatureExperimentService service;

    @AfterEach void close() { if (service != null) service.shutdown(); }

    @Test
    void keepsExactParametersStableOrderMetadataAndBoundsConcurrency() {
        List<GenerationCommand> calls = new CopyOnWriteArrayList<>();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        GenerationClient client = command -> {
            calls.add(command);
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            try { Thread.sleep(command.temperature() == 0 ? 80 : 15); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { active.decrementAndGet(); }
            return new ProviderGeneration("answer-" + command.temperature(), new TokenUsage(3, 4, 7));
        };
        service = new TemperatureExperimentService(client, Executors.newFixedThreadPool(2));

        var result = service.run("same prompt", "gpt-4.1-mini", 512);

        assertThat(calls).extracting(GenerationCommand::prompt).containsOnly("same prompt");
        assertThat(calls).extracting(GenerationCommand::model).containsOnly("gpt-4.1-mini");
        assertThat(calls).extracting(GenerationCommand::maxTokens).containsOnly(512);
        assertThat(calls).extracting(GenerationCommand::temperature).containsExactlyInAnyOrder(0.0, 0.7, 1.2);
        assertThat(peak).hasValueLessThanOrEqualTo(2);
        assertThat(result.results()).extracting(item -> item.temperature()).containsExactly(0.0, 0.7, 1.2);
        assertThat(result.results()).allSatisfy(item -> {
            assertThat(item.usage().totalTokens()).isEqualTo(7);
            assertThat(item.durationMs()).isNotNegative();
        });
        assertThat(result.durationMs()).isNotNegative();
    }

    @Test
    void preservesSuccessfulCallsWhenOneProviderCallFails() {
        GenerationClient client = command -> {
            if (command.temperature() == 0.7) throw new IllegalStateException("secret upstream body");
            return new ProviderGeneration("ok", TokenUsage.EMPTY);
        };
        service = new TemperatureExperimentService(client, Executors.newFixedThreadPool(2));
        var results = service.run("prompt", "gpt-4.1-mini", 100).results();
        assertThat(results).extracting(item -> item.status().name()).containsExactly("SUCCESS", "ERROR", "SUCCESS");
        assertThat(results.get(1).error()).doesNotContain("secret");
    }

    @Test
    void representsAllProviderFailuresInsteadOfThrowing() {
        service = new TemperatureExperimentService(command -> { throw new RuntimeException("raw"); },
                Executors.newFixedThreadPool(2));
        assertThat(service.run("prompt", "gpt-4.1-mini", 100).results())
                .allSatisfy(item -> assertThat(item.status().name()).isEqualTo("ERROR"));
    }
}
