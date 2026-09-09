package com.github.vladsaraykin.aichat.agent.domain;

import java.math.BigDecimal;

public record TokenPricing(BigDecimal inputPerMillionUsd,
                           BigDecimal cachedInputPerMillionUsd,
                           BigDecimal outputPerMillionUsd) {
    public TokenPricing {
        if (inputPerMillionUsd == null || cachedInputPerMillionUsd == null || outputPerMillionUsd == null
                || inputPerMillionUsd.signum() < 0 || cachedInputPerMillionUsd.signum() < 0
                || outputPerMillionUsd.signum() < 0) {
            throw new IllegalArgumentException("Token prices must be non-negative");
        }
        if (cachedInputPerMillionUsd.compareTo(inputPerMillionUsd) > 0) {
            throw new IllegalArgumentException("Cached input price cannot exceed input price");
        }
    }
}
