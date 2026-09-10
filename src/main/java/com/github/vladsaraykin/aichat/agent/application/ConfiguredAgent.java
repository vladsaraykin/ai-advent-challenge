package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.AgentDefinition;
import com.github.vladsaraykin.aichat.agent.domain.ChatMessage;
import com.github.vladsaraykin.aichat.agent.domain.ContextSummary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// One independent, immutable agent instance per YAML definition; conversation state is supplied per chat.
public final class ConfiguredAgent implements Agent {
    private final AgentDefinition definition;
    private final ConversationModel model;
    public ConfiguredAgent(AgentDefinition definition, ConversationModel model) {
        this.definition = definition;
        this.model = model;
    }
    @Override public AgentDefinition definition() { return definition; }

    @Override public ChatMessage answer(List<ChatMessage> history, ChatMessage userMessage) {
        return answer(null, history, userMessage);
    }

    @Override public ChatMessage answer(ContextSummary summary, List<ChatMessage> history,
                                        ChatMessage userMessage) {
        List<ChatMessage> context = context(summary, history, userMessage);
        return message(model.reply(definition, summary, context));
    }

    @Override public Flux<AnswerPart> answerStream(List<ChatMessage> history, ChatMessage userMessage) {
        return answerStream(null, history, userMessage);
    }

    @Override public Flux<AnswerPart> answerStream(ContextSummary summary, List<ChatMessage> history,
                                                   ChatMessage userMessage) {
        List<ChatMessage> context = context(summary, history, userMessage);
        return model.stream(definition, summary, context).map(part -> part.completed() == null
                ? AnswerPart.delta(part.delta()) : AnswerPart.completed(message(part.completed())));
    }

    @Override public Mono<ContextSummary> summarize(ContextSummary previous, List<ChatMessage> messages) {
        return model.summarize(definition, previous, messages).map(reply -> {
            if (reply.text() == null || reply.text().isBlank()) {
                throw new ChatFailure(ChatFailure.Kind.PROVIDER, "Модель вернула пустое summary");
            }
            if ("length".equalsIgnoreCase(reply.metrics().finishReason())) {
                throw new ChatFailure(ChatFailure.Kind.PROVIDER,
                        "Summary достигло лимита токенов и не было сохранено");
            }
            return ContextSummary.updated(previous, reply.text().strip(), messages, reply.metrics());
        });
    }

    private List<ChatMessage> context(ContextSummary summary, List<ChatMessage> history,
                                      ChatMessage userMessage) {
        long characters = definition.systemPrompt().length() + userMessage.content().length()
                + (summary == null ? 0 : summary.content().length())
                + history.stream().mapToLong(message -> message.content().length()).sum();
        if (characters > definition.maxHistoryChars()) {
            throw new ChatFailure(ChatFailure.Kind.INVALID,
                    "Достигнут лимит контекста этого агента. Создайте новый чат; текущая история сохранена.");
        }
        var context = new ArrayList<>(history);
        context.add(userMessage);
        return List.copyOf(context);
    }

    private ChatMessage message(ConversationModel.Reply reply) {
        if (reply.text() == null || reply.text().isBlank()) {
            throw new ChatFailure(ChatFailure.Kind.PROVIDER,
                    "Модель вернула пустой ответ. Проверьте лимит токенов в настройках агента.");
        }
        return new ChatMessage(UUID.randomUUID(), ChatMessage.Role.ASSISTANT,
                reply.text(), Instant.now(), reply.metrics());
    }
}
