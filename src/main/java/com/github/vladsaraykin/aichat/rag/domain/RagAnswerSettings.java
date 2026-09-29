package com.github.vladsaraykin.aichat.rag.domain;

import java.math.BigDecimal;
import com.github.vladsaraykin.aichat.agent.domain.TokenPricing;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.rag.answer")
public record RagAnswerSettings(@DefaultValue("") String model,
        @DefaultValue("4096") int maxCompletionTokens, @DefaultValue("180") int timeoutSeconds,
        @DefaultValue("5") int topK, @DefaultValue("6000") int maxContextTokens,
        BigDecimal inputPerMillionUsd, BigDecimal cachedInputPerMillionUsd, BigDecimal outputPerMillionUsd) {
    public RagAnswerSettings {
        if (model == null) model = "";
        model = model.strip();
        if (model.length() > 128 || maxCompletionTokens < 128 || maxCompletionTokens > 32768
                || timeoutSeconds < 5 || timeoutSeconds > 240 || topK < 1 || topK > 20
                || maxContextTokens < 128 || maxContextTokens > 12000) throw new IllegalArgumentException("Invalid RAG answer configuration");
        boolean any = inputPerMillionUsd != null || cachedInputPerMillionUsd != null || outputPerMillionUsd != null;
        if (any) new TokenPricing(inputPerMillionUsd, cachedInputPerMillionUsd, outputPerMillionUsd);
    }
    public TokenPricing pricing() {
        return inputPerMillionUsd == null ? null : new TokenPricing(inputPerMillionUsd, cachedInputPerMillionUsd, outputPerMillionUsd);
    }
}
