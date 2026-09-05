package com.github.vladsaraykin.aichat.modelcomparison.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ModelTierTest {
    @Test
    void calculatesInputAndOutputCostUsingPerMillionRates() {
        ModelTier luna = ModelTier.selected("WEAK", ModelOption.find("gpt-5.6-luna").orElseThrow());
        assertThat(luna.estimateCost(new TokenUsage(1_000, 2_000, 3_000)))
                .isEqualByComparingTo(new BigDecimal("0.00260000"));
    }
}
