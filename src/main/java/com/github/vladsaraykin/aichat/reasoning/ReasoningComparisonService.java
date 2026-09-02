package com.github.vladsaraykin.aichat.reasoning;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

@Service
public class ReasoningComparisonService {

    private static final Logger logger = LoggerFactory.getLogger(ReasoningComparisonService.class);
    private final ChatModel chatModel;
    private final ExecutorService executor;

    @Autowired
    public ReasoningComparisonService(ChatModel chatModel) {
        this(chatModel, Executors.newFixedThreadPool(2, Thread.ofPlatform()
                .name("reasoning-comparison-", 0).daemon(true).factory()));
    }

    ReasoningComparisonService(ChatModel chatModel, ExecutorService executor) {
        this.chatModel = chatModel;
        this.executor = executor;
    }

    public ReasoningComparison compare(ReasoningComparisonForm form) {
        long comparisonStarted = System.nanoTime();
        logger.info("Starting four-way reasoning comparison: model={}, taskCharacters={}",
                form.getModel(), form.getTask().length());
        List<Future<ApproachResult>> futures = List.of(
                executor.submit(() -> timed(() -> direct(form))),
                executor.submit(() -> timed(() -> stepByStep(form))),
                executor.submit(() -> timed(() -> generatedPrompt(form))),
                executor.submit(() -> timed(() -> expertPanel(form))));

        List<ApproachResult> results = new ArrayList<>(4);
        try {
            for (Future<ApproachResult> future : futures) {
                results.add(future.get());
            }
        } catch (InterruptedException exception) {
            futures.forEach(future -> future.cancel(true));
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Reasoning comparison was interrupted", exception);
        } catch (ExecutionException exception) {
            futures.forEach(future -> future.cancel(true));
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Reasoning comparison failed", cause);
        }

        CallResult comparison = call(form, comparisonPrompt(form.getTask(), results), 700);
        TokenUsage total = results.stream().map(ApproachResult::usage)
                .reduce(TokenUsage.ZERO, TokenUsage::plus).plus(comparison.usage());
        return new ReasoningComparison(form.getTask(), form.getModel(), results, comparison.text(), total,
                elapsedMillis(comparisonStarted));
    }

    private ApproachResult direct(ReasoningComparisonForm form) {
        CallResult result = call(form, form.getTask(), form.getMaxTokens());
        return result(ReasoningApproach.DIRECT, form.getTask(), result);
    }

    private ApproachResult stepByStep(ReasoningComparisonForm form) {
        String prompt = form.getTask() + "\n\nРешай пошагово. Проверь итоговый ответ.";
        return result(ReasoningApproach.STEP_BY_STEP, prompt, call(form, prompt, form.getMaxTokens()));
    }

    private ApproachResult generatedPrompt(ReasoningComparisonForm form) {
        String metaPrompt = "Составь наилучший самодостаточный промпт для решения задачи ниже. "
                + "Верни только текст промпта, не решай задачу.\n\nЗадача:\n" + form.getTask();
        CallResult generated = call(form, metaPrompt, 900);
        CallResult solved = call(form, generated.text(), form.getMaxTokens());
        List<ReasoningTurn> turns = List.of(
                new ReasoningTurn("Пользователь — генерация промпта", metaPrompt),
                new ReasoningTurn("Ассистент — созданный промпт", generated.text()),
                new ReasoningTurn("Пользователь — запуск промпта", generated.text()),
                new ReasoningTurn("Ассистент — решение", solved.text()));
        return new ApproachResult(ReasoningApproach.GENERATED_PROMPT, turns, solved.text(),
                generated.usage().plus(solved.usage()), 0);
    }

    private ApproachResult expertPanel(ReasoningComparisonForm form) {
        String prompt = "Решите задачу группой экспертов. Сначала независимо дайте решения от трёх ролей: "
                + "Аналитик (формализует и решает), Инженер (ищет практичный/алгоритмический путь), "
                + "Критик (проверяет допущения, ошибки и крайние случаи). Затем дайте согласованный итоговый ответ. "
                + "Явно озаглавьте ответ каждого эксперта.\n\nЗадача:\n" + form.getTask();
        return result(ReasoningApproach.EXPERT_PANEL, prompt, call(form, prompt, form.getMaxTokens()));
    }

    private ApproachResult result(ReasoningApproach approach, String prompt, CallResult response) {
        return new ApproachResult(approach, List.of(new ReasoningTurn("Пользователь", prompt),
                new ReasoningTurn("Ассистент", response.text())), response.text(), response.usage(), 0);
    }

    private static ApproachResult timed(Supplier<ApproachResult> operation) {
        long started = System.nanoTime();
        return operation.get().withDuration(elapsedMillis(started));
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private CallResult call(ReasoningComparisonForm form, String userPrompt, int maxTokens) {
        OpenAiChatOptions options = optionsFor(form.getModel(), maxTokens);
        ChatResponse response = chatModel.call(new Prompt(List.of(new UserMessage(userPrompt)), options));
        Usage usage = response.getMetadata().getUsage();
        TokenUsage tokens = usage == null ? TokenUsage.ZERO : new TokenUsage(
                value(usage.getPromptTokens()), value(usage.getCompletionTokens()), value(usage.getTotalTokens()));
        return new CallResult(response.getResult().getOutput().getText(), tokens);
    }

    static OpenAiChatOptions optionsFor(String model, int maxTokens) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(model);
        if (usesReasoningParameters(model)) {
            builder.maxCompletionTokens(maxTokens);
        } else {
            builder.temperature(0.2).maxTokens(maxTokens);
        }
        return builder.build();
    }

    private static boolean usesReasoningParameters(String model) {
        return model.startsWith("gpt-5") || model.matches("o[1-9].*");
    }

    private String comparisonPrompt(String task, List<ApproachResult> results) {
        StringBuilder prompt = new StringBuilder("Сравни четыре решения одной задачи. Кратко укажи различия, "
                + "выбери наиболее точное и объясни выбор. Не считай более длинный ответ автоматически лучшим. "
                + "Если точность нельзя объективно установить, честно скажи об этом. "
                + "Дай короткий связный текст без Markdown-разметки.\n\nЗадача:\n")
                .append(task).append("\n\n");
        for (ApproachResult result : results) {
            prompt.append("### ").append(result.approach().getDisplayName()).append("\n")
                    .append(result.answer()).append("\n\n");
        }
        return prompt.toString();
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private record CallResult(String text, TokenUsage usage) { }
}
