package com.github.vladsaraykin.aichat.agent.domain;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;

public record ChatMessage(UUID id, Role role, String content, Instant createdAt, Metrics metrics) {
    public enum Role { USER, ASSISTANT }
    public record Metrics(String model, long durationMs,
                          Integer currentMessageTokens, Integer historyTokens, Integer systemPromptTokens,
                          int promptTokens, Integer cachedPromptTokens, int completionTokens, int totalTokens,
                          BigDecimal inputCostUsd, BigDecimal outputCostUsd, BigDecimal totalCostUsd,
                          String finishReason) { }
}
