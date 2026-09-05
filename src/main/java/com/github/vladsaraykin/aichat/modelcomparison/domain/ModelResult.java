package com.github.vladsaraykin.aichat.modelcomparison.domain;

import java.math.BigDecimal;

public record ModelResult(ModelTier tier, Status status, String answer, String error,
                          TokenUsage usage, long durationMs, BigDecimal estimatedCostUsd) {
    public enum Status { SUCCESS, ERROR }

    public static ModelResult success(ModelTier tier, String answer, TokenUsage usage, long durationMs) {
        return new ModelResult(tier, Status.SUCCESS, answer, null, usage, durationMs, tier.estimateCost(usage));
    }

    public static ModelResult failure(ModelTier tier, String error, long durationMs) {
        return new ModelResult(tier, Status.ERROR, null, error, TokenUsage.EMPTY, durationMs, BigDecimal.ZERO);
    }

    public static ModelResult failure(ModelTier tier, String error, TokenUsage usage, long durationMs) {
        return new ModelResult(tier, Status.ERROR, null, error, usage, durationMs, tier.estimateCost(usage));
    }
}
