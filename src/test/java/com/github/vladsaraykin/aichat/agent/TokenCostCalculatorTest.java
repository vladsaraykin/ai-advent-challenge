package com.github.vladsaraykin.aichat.agent;

import com.github.vladsaraykin.aichat.agent.domain.TokenCostCalculator;
import com.github.vladsaraykin.aichat.agent.domain.TokenPricing;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TokenCostCalculatorTest {
    private static final TokenPricing PRICING = new TokenPricing(
            new BigDecimal("0.40"), new BigDecimal("0.10"), new BigDecimal("1.60"));

    @Test void pricesUncachedCachedAndOutputTokensPerMillion() {
        var cost = TokenCostCalculator.calculate(PRICING, 1000, 400, 200);
        assertThat(cost.inputUsd()).isEqualByComparingTo("0.00028000");
        assertThat(cost.outputUsd()).isEqualByComparingTo("0.00032000");
        assertThat(cost.totalUsd()).isEqualByComparingTo("0.00060000");
    }
}
