package com.github.vladsaraykin.aichat.rag.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.rag.chunking")
public record ChunkingSettings(@DefaultValue("384") int maxEstimatedTokens,
                               @DefaultValue("64") int overlapEstimatedTokens) {
    public ChunkingSettings {
        if (maxEstimatedTokens < 32 || maxEstimatedTokens > 1024
                || overlapEstimatedTokens < 0 || overlapEstimatedTokens > maxEstimatedTokens / 2) {
            throw new IllegalArgumentException("RAG chunk size must be 32–1024 estimated tokens; overlap 0–half the size");
        }
    }
}
