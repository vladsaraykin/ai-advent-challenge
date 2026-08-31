package com.github.vladsaraykin.aichat.history;

import java.time.Instant;
import java.util.List;

public record ChatConversation(String systemPrompt, List<ChatMessage> messages, Instant updatedAt) {

    public static ChatConversation empty() {
        return new ChatConversation("You are a helpful assistant.", List.of(), Instant.now());
    }

    public ChatConversation {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
