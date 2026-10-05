package com.github.vladsaraykin.aichat.agent.domain;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;

public record ChatMessage(UUID id, Role role, String content, Instant createdAt, Metrics metrics, Evidence evidence) {
    public ChatMessage(UUID id,Role role,String content,Instant createdAt,Metrics metrics) {
        this(id,role,content,createdAt,metrics,null);
    }
    public record Evidence(java.util.List<com.github.vladsaraykin.aichat.rag.domain.RagQuestion.Source> sources,
                           com.github.vladsaraykin.aichat.rag.domain.RagQuestion.Grounding grounding,
                           com.github.vladsaraykin.aichat.rag.domain.RagQuestion.Retrieval retrieval,
                           Metrics rewriteUsage, UUID requestId) {
        public Evidence(java.util.List<com.github.vladsaraykin.aichat.rag.domain.RagQuestion.Source> sources,
                        com.github.vladsaraykin.aichat.rag.domain.RagQuestion.Grounding grounding,
                        com.github.vladsaraykin.aichat.rag.domain.RagQuestion.Retrieval retrieval, Metrics rewriteUsage) {
            this(sources,grounding,retrieval,rewriteUsage,null);
        }
    }
    public enum Role { USER, ASSISTANT }
    public record Metrics(String model, long durationMs,
                          Integer currentMessageTokens, Integer historyTokens, Integer systemPromptTokens,
                          int promptTokens, Integer cachedPromptTokens, int completionTokens, int totalTokens,
                          BigDecimal inputCostUsd, BigDecimal outputCostUsd, BigDecimal totalCostUsd,
                          String finishReason) { }
}
