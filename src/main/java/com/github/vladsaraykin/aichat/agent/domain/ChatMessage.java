package com.github.vladsaraykin.aichat.agent.domain;

import java.time.Instant;
import java.util.UUID;

public record ChatMessage(UUID id, Role role, String content, Instant createdAt, Metrics metrics) {
    public enum Role { USER, ASSISTANT }
    public record Metrics(String model, long durationMs, int promptTokens, int completionTokens,
                          int totalTokens, String finishReason) { }
}
