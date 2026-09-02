package com.github.vladsaraykin.aichat.reasoning;

public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                totalTokens + other.totalTokens);
    }
}
