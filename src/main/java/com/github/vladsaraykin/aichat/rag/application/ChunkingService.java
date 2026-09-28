package com.github.vladsaraykin.aichat.rag.application;

import com.github.vladsaraykin.aichat.rag.domain.*;
import java.util.*;
import java.util.concurrent.Semaphore;

public final class ChunkingService {
    public record Statistics(int chunkCount, int minEstimatedTokens, int maxEstimatedTokens,
                             long totalEstimatedTokens, int sourceEstimatedTokens, long durationMs) { }
    public record Result(DocumentChunk.Strategy strategy, Statistics statistics, List<DocumentChunk> chunks) { }
    public record Preview(UUID documentId, ChunkingSettings settings, String tokenEstimator,
                          int offset, int limit, List<String> warnings, List<Result> results) { }
    private final DocumentService documents;
    private final DocumentChunker chunker;
    private final ChunkTokenEstimator tokens;
    private final ChunkingSettings settings;
    private final Semaphore slot = new Semaphore(1);

    public ChunkingService(DocumentService documents, DocumentChunker chunker,
                           ChunkTokenEstimator tokens, ChunkingSettings settings) {
        this.documents = documents; this.chunker = chunker; this.tokens = tokens; this.settings = settings;
    }

    public Preview preview(String owner, UUID id, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new RagFailure(RagFailure.Kind.INVALID, "limit: 1–100, offset: от 0.");
        var document = documents.get(owner, id); // Ownership checked before any expensive work.
        if (!slot.tryAcquire()) throw new RagFailure(RagFailure.Kind.BUSY, "Разбиение другого документа уже выполняется.");
        try {
            int inputTokens = tokens.count(document.extracted().text());
            var results = new ArrayList<Result>();
            for (var strategy : DocumentChunk.Strategy.values()) {
                long started = System.nanoTime();
                var chunks = chunker.split(document, strategy);
                var stats = chunks.stream().mapToInt(DocumentChunk::estimatedTokens).summaryStatistics();
                var statistics = new Statistics(chunks.size(), chunks.isEmpty() ? 0 : stats.getMin(),
                        chunks.isEmpty() ? 0 : stats.getMax(), stats.getSum(), inputTokens, (System.nanoTime() - started) / 1_000_000);
                results.add(new Result(strategy, statistics, chunks.stream().skip(offset).limit(limit).toList()));
            }
            return new Preview(id, settings, "cl100k_base (оценка, не токены Ollama)", offset, limit,
                    List.of("PDF-заголовки определяются эвристически; списки могут быть ошибочно распознаны как разделы.",
                            "Overlap добавляется только внутри длинного раздела; границы разделов не перекрываются.",
                            "Предпросмотр не сохраняет чанки, не вызывает Ollama и не оценивает качество ответов."), List.copyOf(results));
        } finally { slot.release(); }
    }
}
