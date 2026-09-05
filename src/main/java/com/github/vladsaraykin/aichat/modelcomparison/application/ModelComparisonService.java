package com.github.vladsaraykin.aichat.modelcomparison.application;

import com.github.vladsaraykin.aichat.modelcomparison.domain.ModelComparison;
import com.github.vladsaraykin.aichat.modelcomparison.domain.ModelResult;
import com.github.vladsaraykin.aichat.modelcomparison.domain.ModelTier;
import com.github.vladsaraykin.aichat.modelcomparison.domain.ModelOption;
import com.github.vladsaraykin.aichat.modelcomparison.domain.TokenUsage;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class ModelComparisonService {
    private static final Logger logger = LoggerFactory.getLogger(ModelComparisonService.class);
    private static final String SAFE_ERROR = "Не удалось получить ответ от модели. Попробуйте ещё раз.";
    private static final String EMPTY_RESPONSE_ERROR =
            "Модель не сформировала текст ответа. Увеличьте лимит токенов и повторите запрос.";
    private static final long TIMEOUT_SECONDS = 180;

    private final GenerationClient client;
    private final ExecutorService executor;

    @Autowired
    public ModelComparisonService(GenerationClient client) {
        this(client, Executors.newFixedThreadPool(2,
                Thread.ofPlatform().name("model-comparison-", 0).daemon(true).factory()));
    }

    ModelComparisonService(GenerationClient client, ExecutorService executor) {
        this.client = client;
        this.executor = executor;
    }

    public ModelComparison compare(String prompt, int maxTokens, List<String> modelIds) {
        long started = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        AtomicInteger outboundCalls = new AtomicInteger();
        List<ModelTier> tiers = resolveTiers(modelIds);
        logger.info("comparison_received requestId={} promptLength={} promptPreview=\"{}\" maxTokens={} models={} plannedCalls={}",
                requestId, prompt.length(), promptPreview(prompt), maxTokens, modelIds, tiers.size());
        List<Callable<ModelResult>> tasks = tiers.stream()
                .<Callable<ModelResult>>map(tier -> () -> generate(requestId, prompt, maxTokens, tier, outboundCalls))
                .toList();
        List<ModelResult> results = new ArrayList<>(tiers.size());
        try {
            List<Future<ModelResult>> futures = executor.invokeAll(tasks, TIMEOUT_SECONDS, TimeUnit.SECONDS);
            for (int index = 0; index < futures.size(); index++) {
                Future<ModelResult> future = futures.get(index);
                results.add(future.isCancelled()
                        ? ModelResult.failure(tiers.get(index), SAFE_ERROR, elapsed(started))
                        : future.get());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("comparison_interrupted requestId={} outboundCalls={}", requestId, tiers.size());
            return logSummary(requestId, outboundCalls.get(), failedComparison(prompt, maxTokens, tiers, started));
        } catch (Exception exception) {
            logger.warn("comparison_failed requestId={} errorType={} outboundCalls={}",
                    requestId, exception.getClass().getSimpleName(), tiers.size());
            return logSummary(requestId, outboundCalls.get(), failedComparison(prompt, maxTokens, tiers, started));
        }
        return logSummary(requestId, outboundCalls.get(), comparison(prompt, maxTokens, results, elapsed(started)));
    }

    private ModelResult generate(String requestId, String prompt, int maxTokens, ModelTier tier,
                                 AtomicInteger outboundCalls) {
        long started = System.nanoTime();
        outboundCalls.incrementAndGet();
        logger.info("openai_call_started requestId={} model={} maxTokens={}", requestId, tier.model(), maxTokens);
        try {
            ProviderGeneration generated = client.generate(new GenerationCommand(prompt, tier.model(), maxTokens));
            if (generated.answer() == null || generated.answer().isBlank()) {
                ModelResult result = ModelResult.failure(tier, EMPTY_RESPONSE_ERROR, generated.usage(), elapsed(started));
                logger.warn("openai_call_empty_response requestId={} model={} durationMs={} promptTokens={} completionTokens={} totalTokens={}",
                        requestId, tier.model(), result.durationMs(), generated.usage().promptTokens(),
                        generated.usage().completionTokens(), generated.usage().totalTokens());
                return result;
            }
            ModelResult result = ModelResult.success(tier, generated.answer(), generated.usage(), elapsed(started));
            logger.info("openai_call_completed requestId={} model={} durationMs={} promptTokens={} completionTokens={} totalTokens={} estimatedCostUsd={}",
                    requestId, tier.model(), result.durationMs(), result.usage().promptTokens(),
                    result.usage().completionTokens(), result.usage().totalTokens(), result.estimatedCostUsd());
            return result;
        } catch (RuntimeException exception) {
            ModelResult result = ModelResult.failure(tier, SAFE_ERROR, elapsed(started));
            logger.warn("openai_call_failed requestId={} model={} durationMs={} errorType={}",
                    requestId, tier.model(), result.durationMs(), exception.getClass().getSimpleName());
            return result;
        }
    }

    private static ModelComparison logSummary(String requestId, int outboundCalls, ModelComparison comparison) {
        long successful = comparison.results().stream().filter(result -> result.status() == ModelResult.Status.SUCCESS).count();
        logger.info("comparison_completed requestId={} outboundCalls={} successfulCalls={} failedCalls={} durationMs={} totalTokens={} estimatedTotalCostUsd={}",
                requestId, outboundCalls, successful, comparison.results().size() - successful,
                comparison.durationMs(), comparison.totalUsage().totalTokens(), comparison.estimatedTotalCostUsd());
        return comparison;
    }

    private static String promptPreview(String prompt) {
        String singleLine = prompt.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        return singleLine.length() <= 1_000 ? singleLine : singleLine.substring(0, 1_000) + "…";
    }

    private static ModelComparison failedComparison(String prompt, int maxTokens, List<ModelTier> tiers, long started) {
        List<ModelResult> failures = tiers.stream()
                .map(tier -> ModelResult.failure(tier, SAFE_ERROR, elapsed(started))).toList();
        return comparison(prompt, maxTokens, failures, elapsed(started));
    }

    private static List<ModelTier> resolveTiers(List<String> modelIds) {
        if (modelIds == null || modelIds.size() != 3 || modelIds.stream().distinct().count() != 3) {
            throw new ResponseStatusException(BAD_REQUEST, "Выберите три разные модели");
        }
        String[] levels = {"WEAK", "MEDIUM", "STRONG"};
        return java.util.stream.IntStream.range(0, 3).mapToObj(index -> {
            ModelOption option = ModelOption.find(modelIds.get(index))
                    .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "Неизвестная модель"));
            return ModelTier.selected(levels[index], option);
        }).toList();
    }

    private static ModelComparison comparison(String prompt, int maxTokens, List<ModelResult> results, long duration) {
        TokenUsage usage = results.stream().map(ModelResult::usage).reduce(TokenUsage.EMPTY,
                (left, right) -> new TokenUsage(left.promptTokens() + right.promptTokens(),
                        left.completionTokens() + right.completionTokens(), left.totalTokens() + right.totalTokens()));
        BigDecimal cost = results.stream().map(ModelResult::estimatedCostUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ModelComparison(prompt, maxTokens, results, duration, usage, cost);
    }

    private static long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
