package com.github.vladsaraykin.aichat.modelcomparison.domain;

import java.math.BigDecimal;
import java.util.List;

public record ModelComparison(String prompt, int maxTokens, List<ModelResult> results,
                              long durationMs, TokenUsage totalUsage, BigDecimal estimatedTotalCostUsd) {
    public ModelComparison {
        results = List.copyOf(results);
    }
}
