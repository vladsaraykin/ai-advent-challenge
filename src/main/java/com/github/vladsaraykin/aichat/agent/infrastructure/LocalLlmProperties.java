package com.github.vladsaraykin.aichat.agent.infrastructure;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.llm.local")
public record LocalLlmProperties(boolean enabled, String baseUrl, String model,
        int maxCompletionTokens, int timeoutSeconds, int concurrency, double temperature,
        boolean thinking, boolean toolsEnabled, String structuredOutputInstruction, String questionsInstruction) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public LocalLlmProperties {
        var uri = URI.create(baseUrl);
        if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || model == null || model.isBlank() || maxCompletionTokens < 64 || maxCompletionTokens > 32768
                || timeoutSeconds < 1 || timeoutSeconds > 300 || concurrency < 1 || concurrency > 4
                || !Double.isFinite(temperature) || temperature < 0 || temperature > 2
                || structuredOutputInstruction == null || structuredOutputInstruction.isBlank()
                || questionsInstruction == null || questionsInstruction.isBlank()) {
            throw new IllegalArgumentException("Invalid local LLM configuration");
        }
    }
}
