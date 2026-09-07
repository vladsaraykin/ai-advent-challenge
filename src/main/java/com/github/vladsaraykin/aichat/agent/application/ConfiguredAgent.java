package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.AgentDefinition;
import com.github.vladsaraykin.aichat.agent.domain.ChatMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
        long characters = definition.systemPrompt().length() + userMessage.content().length()
                + history.stream().mapToLong(message -> message.content().length()).sum();
        if (characters > definition.maxHistoryChars()) {
            throw new ChatFailure(ChatFailure.Kind.INVALID,
                    "Достигнут лимит контекста этого агента. Создайте новый чат; текущая история сохранена.");
        }
        var context = new ArrayList<>(history);
        context.add(userMessage);
        ConversationModel.Reply reply = model.reply(definition, List.copyOf(context));
        if (reply.text() == null || reply.text().isBlank()) {
            throw new ChatFailure(ChatFailure.Kind.PROVIDER,
                    "Модель вернула пустой ответ. Проверьте лимит токенов в настройках агента.");
        }
        return new ChatMessage(UUID.randomUUID(), ChatMessage.Role.ASSISTANT,
                reply.text(), Instant.now(), reply.metrics());
    }
}
