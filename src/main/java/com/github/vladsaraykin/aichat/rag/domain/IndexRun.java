package com.github.vladsaraykin.aichat.rag.domain;

import java.time.Instant;
import java.util.UUID;

public record IndexRun(UUID id, UUID documentId, DocumentChunk.Strategy strategy, String status,
                       String embeddingModel, int dimensions, int totalChunks, int embeddedChunks,
                       Long promptTokens, Long durationMs, String errorCode, Instant createdAt,
                       Instant finishedAt) { }
