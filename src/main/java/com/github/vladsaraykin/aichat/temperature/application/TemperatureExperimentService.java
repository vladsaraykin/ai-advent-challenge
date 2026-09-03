package com.github.vladsaraykin.aichat.temperature.application;

import com.github.vladsaraykin.aichat.temperature.domain.GenerationResult;
import com.github.vladsaraykin.aichat.temperature.domain.TemperatureExperiment;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TemperatureExperimentService {
    private static final Logger logger = LoggerFactory.getLogger(TemperatureExperimentService.class);
    public static final List<Double> TEMPERATURES = List.of(0.0, 0.7, 1.2);
    private static final long EXPERIMENT_TIMEOUT_SECONDS = 120;
    private static final String SAFE_ERROR = "Не удалось получить ответ от модели. Попробуйте ещё раз.";

    private final GenerationClient client;
    private final ExecutorService executor;

    @Autowired
    public TemperatureExperimentService(GenerationClient client) {
        this(client, Executors.newFixedThreadPool(2,
                Thread.ofPlatform().name("temperature-experiment-", 0).daemon(true).factory()));
    }

    TemperatureExperimentService(GenerationClient client, ExecutorService executor) {
        this.client = client;
        this.executor = executor;
    }

    public TemperatureExperiment run(String prompt, String model, int maxTokens) {
        long started = System.nanoTime();
        List<Callable<GenerationResult>> tasks = TEMPERATURES.stream()
                .<Callable<GenerationResult>>map(temperature ->
                        () -> generate(prompt, model, maxTokens, temperature))
                .toList();
        List<GenerationResult> ordered = new ArrayList<>(TEMPERATURES.size());
        List<Future<GenerationResult>> futures;
        try {
            futures = executor.invokeAll(tasks, EXPERIMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return timedOutExperiment(prompt, model, maxTokens, started);
        }
        for (int index = 0; index < futures.size(); index++) {
            try {
                Future<GenerationResult> future = futures.get(index);
                ordered.add(future.isCancelled()
                        ? GenerationResult.failure(TEMPERATURES.get(index), SAFE_ERROR, elapsed(started))
                        : future.get());
            } catch (InterruptedException exception) {
                futures.forEach(future -> future.cancel(true));
                Thread.currentThread().interrupt();
                ordered.add(GenerationResult.failure(TEMPERATURES.get(index), SAFE_ERROR, 0));
            } catch (Exception exception) {
                ordered.add(GenerationResult.failure(TEMPERATURES.get(index), SAFE_ERROR, 0));
            }
        }
        return new TemperatureExperiment(prompt, model, maxTokens, ordered, elapsed(started));
    }

    private static TemperatureExperiment timedOutExperiment(String prompt, String model, int maxTokens, long started) {
        List<GenerationResult> failures = TEMPERATURES.stream()
                .map(temperature -> GenerationResult.failure(temperature, SAFE_ERROR, elapsed(started)))
                .toList();
        return new TemperatureExperiment(prompt, model, maxTokens, failures, elapsed(started));
    }

    private GenerationResult generate(String prompt, String model, int maxTokens, double temperature) {
        long started = System.nanoTime();
        try {
            ProviderGeneration generated = client.generate(new GenerationCommand(prompt, model, maxTokens, temperature));
            return GenerationResult.success(temperature, generated.answer(), generated.usage(), elapsed(started));
        } catch (RuntimeException exception) {
            logger.warn("OpenAI generation failed for temperature={}: {}",
                    temperature, exception.getClass().getSimpleName());
            return GenerationResult.failure(temperature, SAFE_ERROR, elapsed(started));
        }
    }

    private static long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
