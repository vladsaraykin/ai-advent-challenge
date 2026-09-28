package com.github.vladsaraykin.aichat.rag.infrastructure;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Optional local smoke check. Never downloads or commits a user's document. */
@EnabledIfEnvironmentVariable(named = "RAG_TEST_PDF", matches = ".+")
class ExternalDocumentTest {
    @Test void extractsChosenPdfWithoutOcr() throws Exception {
        Path file = Path.of(System.getenv("RAG_TEST_PDF"));
        assertThat(Files.size(file)).isLessThanOrEqualTo(20L * 1024 * 1024);
        var result = new OfficeDocumentExtractor().extract(file, "pdf");
        assertThat(result.text()).isNotBlank();
        assertThat(result.metadata().pageCount()).isPositive();
        for (var block : result.metadata().blocks()) {
            assertThat(result.text().substring(block.start(), block.end())).isNotBlank();
        }
        System.out.printf("RAG PDF check: pages=%d characters=%d blocks=%d%n", result.metadata().pageCount(),
                result.text().length(), result.metadata().blocks().size());
        var estimator = new RagConfiguration().ragChunkTokenEstimator();
        var chunker = new com.github.vladsaraykin.aichat.rag.application.DocumentChunker(estimator,
                new com.github.vladsaraykin.aichat.rag.domain.ChunkingSettings(384, 64));
        var doc = new com.github.vladsaraykin.aichat.rag.domain.RagDocument(java.util.UUID.randomUUID(), "test",
                "test.pdf", "application/pdf", java.util.UUID.randomUUID(), Files.size(file), "a".repeat(64), result, java.time.Instant.now());
        for (var strategy : com.github.vladsaraykin.aichat.rag.domain.DocumentChunk.Strategy.values()) {
            var chunks = chunker.split(doc, strategy);
            assertThat(chunks).isNotEmpty().allSatisfy(chunk -> {
                assertThat(chunk.estimatedTokens()).isLessThanOrEqualTo(384);
                assertThat(chunk.content()).isEqualTo(result.text().substring(chunk.startOffset(), chunk.endOffset()));
            });
            System.out.printf("RAG chunk check: strategy=%s chunks=%d estimatedTokens=%d%n", strategy, chunks.size(),
                    chunks.stream().mapToLong(com.github.vladsaraykin.aichat.rag.domain.DocumentChunk::estimatedTokens).sum());
        }
    }
}
