package com.github.vladsaraykin.aichat.agent.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class TokenCostCalculator {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private TokenCostCalculator() { }

    public static Cost calculate(TokenPricing pricing, int promptTokens,
                                 int cachedPromptTokens, int completionTokens) {
        int cached = Math.max(0, Math.min(promptTokens, cachedPromptTokens));
        int uncached = Math.max(0, promptTokens - cached);
        BigDecimal input = tokenCost(uncached, pricing.inputPerMillionUsd())
                .add(tokenCost(cached, pricing.cachedInputPerMillionUsd()))
                .setScale(8, RoundingMode.HALF_UP);
        BigDecimal output = tokenCost(Math.max(0, completionTokens), pricing.outputPerMillionUsd());
        return new Cost(input, output, input.add(output).setScale(8, RoundingMode.HALF_UP));
    }

    private static BigDecimal tokenCost(int tokens, BigDecimal pricePerMillion) {
        return pricePerMillion.multiply(BigDecimal.valueOf(tokens))
                .divide(MILLION, 8, RoundingMode.HALF_UP);
    }

    public record Cost(BigDecimal inputUsd, BigDecimal outputUsd, BigDecimal totalUsd) { }
}
