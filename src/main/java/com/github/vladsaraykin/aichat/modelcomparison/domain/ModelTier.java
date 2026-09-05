package com.github.vladsaraykin.aichat.modelcomparison.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record ModelTier(String level, String model, String displayName,
                        BigDecimal inputUsdPerMillion, BigDecimal outputUsdPerMillion) {

    public static ModelTier selected(String level, ModelOption option) {
        return new ModelTier(level, option.model(), option.displayName(),
                option.inputUsdPerMillion(), option.outputUsdPerMillion());
    }

    public BigDecimal estimateCost(TokenUsage usage) {
        BigDecimal input = inputUsdPerMillion.multiply(BigDecimal.valueOf(usage.promptTokens()));
        BigDecimal output = outputUsdPerMillion.multiply(BigDecimal.valueOf(usage.completionTokens()));
        return input.add(output).divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
    }
}
