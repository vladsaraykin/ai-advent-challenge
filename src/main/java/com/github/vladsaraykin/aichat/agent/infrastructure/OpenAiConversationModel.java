package com.github.vladsaraykin.aichat.agent.infrastructure;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class OpenAiConversationModel implements ConversationModel {
    private static final Logger log = LoggerFactory.getLogger(OpenAiConversationModel.class);
    private final ChatModel model;
    private final TokenCounter tokenCounter;
    public OpenAiConversationModel(ChatModel model, TokenCounter tokenCounter) {
        this.model = model;
        this.tokenCounter = tokenCounter;
    }

    @Override public Reply reply(AgentDefinition definition, List<ChatMessage> messages) {
        return reply(definition, null, messages);
    }

    @Override public Reply reply(AgentDefinition definition, ContextSummary summary, List<ChatMessage> messages) {
        long started = System.nanoTime();
        try {
            log.info("llm_started agentId={} model={} contextMessages={} maxCompletionTokens={} outboundCalls=1",
                    definition.id(), definition.model(), messages.size() + 1, definition.maxCompletionTokens());
            var response = model.call(prompt(definition, summary, messages, false));
            if (response == null || response.getResult() == null) {
                throw new ChatFailure(ChatFailure.Kind.PROVIDER, "Модель не вернула ответ");
            }
            var usage = response.getMetadata().getUsage();
            String finishReason = response.getResult().getMetadata().getFinishReason();
            int promptTokens = usage == null ? 0 : value(usage.getPromptTokens());
            int completionTokens = usage == null ? 0 : value(usage.getCompletionTokens());
            int cachedPromptTokens = usage == null || usage.getCacheReadInputTokens() == null
                    ? 0 : Math.toIntExact(usage.getCacheReadInputTokens());
            int currentMessageTokens = messages.isEmpty() ? 0
                    : tokenCounter.count(definition.model(), messages.getLast().content());
            int historyTokens = (summary == null ? 0 : tokenCounter.count(definition.model(), summary.content()))
                    + messages.stream().limit(Math.max(0, messages.size() - 1L))
                            .mapToInt(message -> tokenCounter.count(definition.model(), message.content())).sum();
            int systemPromptTokens = tokenCounter.count(definition.model(), definition.systemPrompt());
            boolean hasUsage = promptTokens > 0 || completionTokens > 0
                    || (usage != null && value(usage.getTotalTokens()) > 0);
            var cost = hasUsage ? TokenCostCalculator.calculate(definition.pricing(), promptTokens,
                    cachedPromptTokens, completionTokens) : null;
            var metrics = new ChatMessage.Metrics(definition.model(),
                    (System.nanoTime() - started) / 1_000_000,
                    currentMessageTokens, historyTokens, systemPromptTokens,
                    promptTokens, cachedPromptTokens, completionTokens,
                    usage == null ? 0 : value(usage.getTotalTokens()),
                    cost == null ? null : cost.inputUsd(), cost == null ? null : cost.outputUsd(),
                    cost == null ? null : cost.totalUsd(),
                    finishReason == null ? null : finishReason.toLowerCase(java.util.Locale.ROOT));
            log.info("llm_completed agentId={} model={} durationMs={} promptTokens={} cachedPromptTokens={} "
                            + "completionTokens={} totalTokens={} costUsd={} finishReason={}",
                    definition.id(), definition.model(), metrics.durationMs(), metrics.promptTokens(),
                    metrics.cachedPromptTokens(), metrics.completionTokens(), metrics.totalTokens(),
                    metrics.totalCostUsd(), metrics.finishReason());
            return new Reply(response.getResult().getOutput().getText(), metrics);
        } catch (ChatFailure exception) { throw exception; }
        catch (RuntimeException exception) {
            log.warn("llm_failed agentId={} model={} errorType={}", definition.id(),
                    definition.model(), exception.getClass().getSimpleName());
            throw new ChatFailure(ChatFailure.Kind.PROVIDER, "Не удалось получить ответ от OpenAI. Попробуйте ещё раз.");
        }
    }

    @Override public Flux<StreamPart> stream(AgentDefinition definition, List<ChatMessage> messages) {
        return stream(definition, null, messages);
    }

    @Override public Flux<StreamPart> stream(AgentDefinition definition, ContextSummary summary,
                                             List<ChatMessage> messages) {
        return streamCall(definition, summary, messages, prompt(definition, summary, messages, true),
                definition.systemPrompt(), "llm_stream");
    }

    @Override public Mono<Reply> summarize(AgentDefinition definition, ContextSummary previous,
                                           List<ChatMessage> messages) {
        var compression = definition.compression();
        return streamCall(definition, previous, messages, summaryPrompt(definition, previous, messages),
                        compression.systemPrompt(), "llm_summary")
                .filter(part -> part.completed() != null).map(StreamPart::completed).single();
    }

    private Flux<StreamPart> streamCall(AgentDefinition definition, ContextSummary summary,
                                        List<ChatMessage> messages, Prompt request,
                                        String systemPrompt, String operation) {
        return Flux.defer(() -> {
            long started = System.nanoTime();
            var text = new StringBuilder();
            var promptTokens = new AtomicInteger();
            var completionTokens = new AtomicInteger();
            var totalTokens = new AtomicInteger();
            var cachedPromptTokens = new AtomicInteger();
            var finishReason = new AtomicReference<String>();
            log.info("{}_started agentId={} model={} contextMessages={} outboundCalls=1",
                    operation, definition.id(), definition.model(), request.getInstructions().size());
            Flux<StreamPart> deltas = model.stream(request).map(response -> {
                if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                    var usage = response.getMetadata().getUsage();
                    promptTokens.set(Math.max(promptTokens.get(), value(usage.getPromptTokens())));
                    completionTokens.set(Math.max(completionTokens.get(), value(usage.getCompletionTokens())));
                    totalTokens.set(Math.max(totalTokens.get(), value(usage.getTotalTokens())));
                    if (usage.getCacheReadInputTokens() != null) {
                        cachedPromptTokens.set(Math.max(cachedPromptTokens.get(),
                                Math.toIntExact(usage.getCacheReadInputTokens())));
                    }
                }
                String delta = "";
                if (response.getResult() != null) {
                    String reason = response.getResult().getMetadata().getFinishReason();
                    if (reason != null && !reason.isBlank()) finishReason.set(reason);
                    if (response.getResult().getOutput().getText() != null) {
                        delta = response.getResult().getOutput().getText();
                    }
                }
                text.append(delta);
                return StreamPart.delta(delta);
            }).filter(part -> part.delta() != null && !part.delta().isEmpty());
            return deltas.concatWith(Mono.fromSupplier(() -> {
                var metrics = metrics(definition, summary, messages, systemPrompt, started, promptTokens.get(),
                        cachedPromptTokens.get(), completionTokens.get(), totalTokens.get(), finishReason.get());
                log.info("{}_completed agentId={} model={} durationMs={} promptTokens={} cachedPromptTokens={} "
                                + "completionTokens={} totalTokens={} costUsd={} finishReason={}",
                        operation, definition.id(), definition.model(), metrics.durationMs(), metrics.promptTokens(),
                        metrics.cachedPromptTokens(), metrics.completionTokens(), metrics.totalTokens(),
                        metrics.totalCostUsd(), metrics.finishReason());
                return StreamPart.completed(new Reply(text.toString(), metrics));
            }));
        }).onErrorMap(exception -> providerFailure(definition, exception, operation));
    }

    private ChatMessage.Metrics metrics(AgentDefinition definition, ContextSummary summary,
                                        List<ChatMessage> messages, String systemPrompt, long started,
                                        int promptTokens, int cachedPromptTokens, int completionTokens,
                                        int totalTokens, String finishReason) {
        int currentMessageTokens = messages.isEmpty() ? 0
                : tokenCounter.count(definition.model(), messages.getLast().content());
        int historyTokens = (summary == null ? 0 : tokenCounter.count(definition.model(), summary.content()))
                + messages.stream().limit(Math.max(0, messages.size() - 1L))
                        .mapToInt(message -> tokenCounter.count(definition.model(), message.content())).sum();
        int systemPromptTokens = tokenCounter.count(definition.model(), systemPrompt);
        boolean hasUsage = promptTokens > 0 || completionTokens > 0 || totalTokens > 0;
        var cost = hasUsage ? TokenCostCalculator.calculate(definition.pricing(), promptTokens,
                cachedPromptTokens, completionTokens) : null;
        return new ChatMessage.Metrics(definition.model(), (System.nanoTime() - started) / 1_000_000,
                currentMessageTokens, historyTokens, systemPromptTokens,
                promptTokens, cachedPromptTokens, completionTokens, totalTokens,
                cost == null ? null : cost.inputUsd(), cost == null ? null : cost.outputUsd(),
                cost == null ? null : cost.totalUsd(),
                finishReason == null ? null : finishReason.toLowerCase(java.util.Locale.ROOT));
    }

    private RuntimeException providerFailure(AgentDefinition definition, Throwable exception, String operation) {
        if (exception instanceof ChatFailure failure) return failure;
        log.warn("{}_failed agentId={} model={} errorType={}", operation, definition.id(),
                definition.model(), exception.getClass().getSimpleName());
        return new ChatFailure(ChatFailure.Kind.PROVIDER, "Не удалось получить ответ от OpenAI. Попробуйте ещё раз.");
    }

    static Prompt prompt(AgentDefinition definition, List<ChatMessage> history) {
        return prompt(definition, null, history, false);
    }

    static Prompt prompt(AgentDefinition definition, ContextSummary summary,
                         List<ChatMessage> history, boolean stream) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(definition.systemPrompt()));
        if (summary != null) {
            messages.add(new SystemMessage("Краткая память предыдущей части диалога:\n<summary>\n"
                    + summary.content() + "\n</summary>"));
        }
        for (ChatMessage message : history) {
            messages.add(message.role() == ChatMessage.Role.USER
                    ? new UserMessage(message.content()) : new AssistantMessage(message.content()));
        }
        var options = OpenAiChatOptions.builder().model(definition.model())
                .maxCompletionTokens(definition.maxCompletionTokens())
                .timeout(Duration.ofSeconds(definition.timeoutSeconds())).maxRetries(0);
        if (stream) options.streamUsage(true);
        if (definition.reasoningEffort() != null && !definition.reasoningEffort().isBlank()) {
            options.reasoningEffort(definition.reasoningEffort());
        }
        return new Prompt(messages, options.build());
    }

    static Prompt summaryPrompt(AgentDefinition definition, ContextSummary previous,
                                List<ChatMessage> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(definition.compression().systemPrompt()));
        if (previous != null) {
            messages.add(new SystemMessage("Существующее summary:\n<summary>\n"
                    + previous.content() + "\n</summary>"));
        }
        for (ChatMessage message : history) {
            messages.add(message.role() == ChatMessage.Role.USER
                    ? new UserMessage(message.content()) : new AssistantMessage(message.content()));
        }
        messages.add(new UserMessage("Обнови summary по переданным сообщениям. Верни только итоговый текст summary."));
        var options = OpenAiChatOptions.builder().model(definition.model())
                .maxCompletionTokens(definition.compression().maxCompletionTokens())
                .timeout(Duration.ofSeconds(definition.timeoutSeconds())).maxRetries(0).streamUsage(true);
        if (definition.reasoningEffort() != null && !definition.reasoningEffort().isBlank()) {
            options.reasoningEffort(definition.reasoningEffort());
        }
        return new Prompt(messages, options.build());
    }
    private static int value(Integer value) { return value == null ? 0 : value; }
}
