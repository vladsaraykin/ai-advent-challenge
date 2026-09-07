package com.github.vladsaraykin.aichat.agent.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Chat(UUID id, String agentId, String title, Instant createdAt,
                   Instant updatedAt, List<ChatMessage> messages) {
    public Chat { messages = List.copyOf(messages); }
    public static Chat create(String agentId) {
        Instant now = Instant.now();
        return new Chat(UUID.randomUUID(), agentId, "Новый чат", now, now, List.of());
    }
    public Chat append(ChatMessage user, ChatMessage assistant) {
        var updated = new java.util.ArrayList<>(messages);
        updated.add(user);
        updated.add(assistant);
        String nextTitle = messages.isEmpty() ? user.content().replaceAll("\\s+", " ").strip() : title;
        if (nextTitle.length() > 70) nextTitle = nextTitle.substring(0, 70) + "…";
        return new Chat(id, agentId, nextTitle, createdAt, assistant.createdAt(), updated);
    }
}
