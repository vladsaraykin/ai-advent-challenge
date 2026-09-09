package com.github.vladsaraykin.aichat.agent.domain;

public record AgentDefinition(String id, String name, String description, String model,
                              String systemPrompt, int maxCompletionTokens,
                              String reasoningEffort, int timeoutSeconds, int maxHistoryChars,
                              TokenPricing pricing) {
    public AgentDefinition {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("Invalid agent id");
        }
        if (name == null || name.isBlank() || description == null || description.isBlank()
                || model == null || model.isBlank() || systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("Agent name, description, model and systemPrompt are required");
        }
        if (pricing == null) throw new IllegalArgumentException("Agent token pricing is required");
        if (maxCompletionTokens < 64 || maxCompletionTokens > 32768
                || timeoutSeconds < 1 || timeoutSeconds > 300
                || maxHistoryChars < 1000 || maxHistoryChars > 200000) {
            throw new IllegalArgumentException("Invalid agent limits");
        }
        if (reasoningEffort != null && !reasoningEffort.isBlank()
                && (!model.startsWith("gpt-5") || !java.util.Set.of("low", "medium", "high").contains(reasoningEffort))) {
            throw new IllegalArgumentException("reasoningEffort supports low/medium/high for GPT-5 models");
        }
    }
}
