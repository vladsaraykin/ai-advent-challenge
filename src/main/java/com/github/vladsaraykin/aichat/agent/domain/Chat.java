package com.github.vladsaraykin.aichat.agent.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Chat(UUID id, String agentId, String title, Instant createdAt,
                   Instant updatedAt, ContextSummary summary, List<ChatMessage> messages) {
    public Chat(UUID id, String agentId, String title, Instant createdAt,
                Instant updatedAt, List<ChatMessage> messages) {
        this(id, agentId, title, createdAt, updatedAt, null, messages);
    }
    public Chat { messages = List.copyOf(messages); }
    public static Chat create(String agentId) {
        Instant now = Instant.now();
        return new Chat(UUID.randomUUID(), agentId, "Новый чат", now, now, null, List.of());
    }
    public Chat append(ChatMessage user, ChatMessage assistant) {
        var updated = new java.util.ArrayList<>(messages);
        updated.add(user);
        updated.add(assistant);
        String nextTitle = messages.isEmpty() ? user.content().replaceAll("\\s+", " ").strip() : title;
        if (nextTitle.length() > 70) nextTitle = nextTitle.substring(0, 70) + "…";
        return new Chat(id, agentId, nextTitle, createdAt, assistant.createdAt(), summary, updated);
    }
    public Chat compact(ContextSummary updatedSummary, int removedMessages) {
        if (removedMessages < 1 || removedMessages > messages.size()) {
            throw new IllegalArgumentException("Invalid compacted message count");
        }
        return new Chat(id, agentId, title, createdAt, updatedAt, updatedSummary,
                messages.subList(removedMessages, messages.size()));
    }

    public ContextSummary.UserMessageMatch matchUserMessage(UUID messageId, String content) {
        var current = messages.stream().filter(message -> message.id().equals(messageId)).findFirst();
        if (current.isPresent()) {
            return current.get().role() == ChatMessage.Role.USER && current.get().content().equals(content)
                    ? ContextSummary.UserMessageMatch.SAME : ContextSummary.UserMessageMatch.CONFLICT;
        }
        return summary == null ? ContextSummary.UserMessageMatch.NONE : summary.match(messageId, content);
    }

    public int messageCount() {
        return messages.size() + (summary == null ? 0 : summary.summarizedMessages());
    }
}
