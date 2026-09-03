package com.github.vladsaraykin.aichat.temperature.domain;

public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
    public static final TokenUsage EMPTY = new TokenUsage(0, 0, 0);
}
