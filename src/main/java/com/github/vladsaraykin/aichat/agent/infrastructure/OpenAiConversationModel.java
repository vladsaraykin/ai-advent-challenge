package com.github.vladsaraykin.aichat.agent.infrastructure;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

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
        long started = System.nanoTime();
        try {
            log.info("llm_started agentId={} model={} contextMessages={} maxCompletionTokens={} outboundCalls=1",
                    definition.id(), definition.model(), messages.size() + 1, definition.maxCompletionTokens());
            var response = model.call(prompt(definition, messages));
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
            int historyTokens = messages.stream().limit(Math.max(0, messages.size() - 1L))
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

    static Prompt prompt(AgentDefinition definition, List<ChatMessage> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(definition.systemPrompt()));
        for (ChatMessage message : history) {
            messages.add(message.role() == ChatMessage.Role.USER
                    ? new UserMessage(message.content()) : new AssistantMessage(message.content()));
        }
        var options = OpenAiChatOptions.builder().model(definition.model())
                .maxCompletionTokens(definition.maxCompletionTokens())
                .timeout(Duration.ofSeconds(definition.timeoutSeconds())).maxRetries(0);
        if (definition.reasoningEffort() != null && !definition.reasoningEffort().isBlank()) {
            options.reasoningEffort(definition.reasoningEffort());
        }
        return new Prompt(messages, options.build());
    }
    private static int value(Integer value) { return value == null ? 0 : value; }
}
