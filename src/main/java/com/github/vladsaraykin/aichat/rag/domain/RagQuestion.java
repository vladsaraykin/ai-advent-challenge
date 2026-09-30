package com.github.vladsaraykin.aichat.rag.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public record RagQuestion(UUID id, UUID indexId, String question, Mode mode, String status,
                          List<Answer> answers, Instant createdAt, RetrievalOptions retrievalOptions) {
    public RagQuestion(UUID id, UUID indexId, String question, Mode mode, String status, List<Answer> answers, Instant createdAt) {
        this(id,indexId,question,mode,status,answers,createdAt,null);
    }
    public enum Mode { WITHOUT_RAG, WITH_RAG, BOTH, RERANKED, REWRITTEN, COMPARE }
    public record Candidate(Source source, Double rerankerScore, String decision) { }
    public record Retrieval(String searchQuery, long rerankingMs, Metrics rewriteMetrics,
                            RetrievalOptions options, List<Candidate> candidates) { }
    public record Source(int number, DocumentChunk chunk, double similarity) { }
    public record Metrics(String model, long generationMs, long embeddingMs, long searchMs,
                          Long embeddingTokens, Integer promptTokens, Integer completionTokens,
                          Integer totalTokens, Integer cachedPromptTokens, BigDecimal costUsd, String finishReason) { }
    public record Answer(Mode mode, String text, List<Source> sources, Metrics metrics, String error, Retrieval retrieval) {
        public Answer(Mode mode, String text, List<Source> sources, Metrics metrics, String error) {
            this(mode,text,sources,metrics,error,null);
        }
    }
}
