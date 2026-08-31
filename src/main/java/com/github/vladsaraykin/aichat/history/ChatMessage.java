package com.github.vladsaraykin.aichat.history;

import java.time.Instant;

public record ChatMessage(Role role, String content, Instant createdAt) {

    public enum Role {
        USER, ASSISTANT
    }
}
