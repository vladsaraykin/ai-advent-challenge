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
    public OpenAiConversationModel(ChatModel model) { this.model = model; }

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
            var metrics = new ChatMessage.Metrics(definition.model(),
                    (System.nanoTime() - started) / 1_000_000,
                    usage == null ? 0 : value(usage.getPromptTokens()),
                    usage == null ? 0 : value(usage.getCompletionTokens()),
                    usage == null ? 0 : value(usage.getTotalTokens()),
                    finishReason == null ? null : finishReason.toLowerCase(java.util.Locale.ROOT));
            log.info("llm_completed agentId={} model={} durationMs={} totalTokens={} finishReason={}",
                    definition.id(), definition.model(), metrics.durationMs(), metrics.totalTokens(), metrics.finishReason());
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
