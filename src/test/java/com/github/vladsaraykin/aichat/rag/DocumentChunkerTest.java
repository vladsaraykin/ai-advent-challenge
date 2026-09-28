package com.github.vladsaraykin.aichat.rag;

import com.github.vladsaraykin.aichat.rag.application.*;
import com.github.vladsaraykin.aichat.rag.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentChunkerTest {
    private final ChunkTokenEstimator estimator = text -> text.codePointCount(0, text.length());
    private final ChunkingSettings settings = new ChunkingSettings(64, 12);
    private final DocumentChunker chunker = new DocumentChunker(estimator, settings);

    @Test void bothStrategiesPreserveExactTextAndUnicodeWithBoundedOverlapAndStableIds() {
        String text = "Требования\n\n" + "Java 😀 PostgreSQL уведомления.\n\n".repeat(20)
                + "Решения\n\n" + "Очередь и повторные попытки.\n\n".repeat(20);
        int second = text.indexOf("Решения");
        var doc = document(text, "docx", List.of(new ExtractedText.Block("HEADING", "Требования", null, 0, 10),
                new ExtractedText.Block("HEADING", "Решения", null, second, second + 7)));
        for (var strategy : DocumentChunk.Strategy.values()) {
            var chunks = chunker.split(doc, strategy);
            assertThat(chunks).isEqualTo(chunker.split(doc, strategy));
            assertThat(chunks.stream().map(DocumentChunk::chunkId).distinct().count()).isEqualTo(chunks.size());
            int covered = 0;
            for (int i = 0; i < chunks.size(); i++) {
                var chunk = chunks.get(i);
                assertThat(chunk.ordinal()).isEqualTo(i);
                assertThat(chunk.source()).isEqualTo("test.docx");
                assertThat(chunk.content()).isEqualTo(text.substring(chunk.startOffset(), chunk.endOffset()));
                assertThat(chunk.estimatedTokens()).isLessThanOrEqualTo(64);
                assertThat(Character.isLowSurrogate(chunk.content().charAt(0))).isFalse();
                assertThat(Character.isHighSurrogate(chunk.content().charAt(chunk.content().length() - 1))).isFalse();
                assertThat(chunk.startOffset()).isLessThanOrEqualTo(covered);
                if (i > 0) {
                    assertThat(chunk.startOffset()).isGreaterThan(chunks.get(i - 1).startOffset());
                    assertThat(estimator.count(text.substring(chunk.startOffset(), covered))).isLessThanOrEqualTo(12);
                }
                covered = chunk.endOffset();
                if (strategy == DocumentChunk.Strategy.STRUCTURAL) {
                    assertThat(chunk.startOffset() < second && chunk.endOffset() > second).isFalse();
                    assertThat(chunk.section()).isEqualTo(chunk.startOffset() < second ? "Требования" : "Решения");
                }
            }
            assertThat(covered).isEqualTo(text.length());
        }
    }

    @Test void structuralRetainsShortSectionsAndFixedSizeCrossesTheirBoundaries() {
        String text = "Первый\n\nОписание.\n\nВторой\n\nРешение.";
        int second = text.indexOf("Второй");
        var doc = document(text, "docx", List.of(new ExtractedText.Block("HEADING", "Первый", null, 0, 6),
                new ExtractedText.Block("HEADING", "Второй", null, second, second + 6)));
        assertThat(chunker.split(doc, DocumentChunk.Strategy.FIXED_SIZE)).hasSize(1);
        assertThat(chunker.split(doc, DocumentChunk.Strategy.STRUCTURAL)).hasSize(2)
                .extracting(DocumentChunk::section).containsExactly("Первый", "Второй");
    }

    @Test void pdfUsesHeadingHeuristicButNotPageBreaksAsSections() {
        String text = "Введение\n\n1. Требования\n" + "Текст ".repeat(30) + "\n2. Архитектура\n" + "Решение ".repeat(30);
        var doc = document(text, "pdf", List.of(new ExtractedText.Block("PAGE", "", 1, 0, 100),
                new ExtractedText.Block("PAGE", "", 2, 102, text.length())));
        var chunks = chunker.split(doc, DocumentChunk.Strategy.STRUCTURAL);
        assertThat(chunks).extracting(DocumentChunk::section).contains("1. Требования", "2. Архитектура");
        assertThat(chunks).anyMatch(c -> Integer.valueOf(1).equals(c.pageStart()) && Integer.valueOf(2).equals(c.pageEnd()));
    }

    @Test void zeroOverlapMissingHeadingsEmptyTextAndSettingsValidation() {
        var doc = document("😀слово ".repeat(40), "docx", List.of());
        var noOverlap = new DocumentChunker(estimator, new ChunkingSettings(32, 0));
        for (var strategy : DocumentChunk.Strategy.values()) {
            var chunks = noOverlap.split(doc, strategy);
            assertThat(String.join("", chunks.stream().map(DocumentChunk::content).toList())).isEqualTo(doc.extracted().text());
        }
        assertThatThrownBy(() -> chunker.split(document("  ", "pdf", List.of()), DocumentChunk.Strategy.FIXED_SIZE))
                .isInstanceOf(RagFailure.class);
        assertThatThrownBy(() -> new ChunkingSettings(31, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkingSettings(64, 33)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void previewUsesAuthenticatedDocumentBoundsOutputAndDoesNotPersistAnything() {
        var documents = mock(DocumentService.class);
        var doc = document("Длинный текст. ".repeat(200), "pdf", List.of());
        when(documents.get("alice", doc.id())).thenReturn(doc);
        var service = new ChunkingService(documents, chunker, estimator, settings);
        var preview = service.preview("alice", doc.id(), 1, 2);
        assertThat(preview.results()).hasSize(2);
        assertThat(preview.results()).allSatisfy(r -> {
            assertThat(r.chunks()).hasSize(2);
            assertThat(r.chunks().getFirst().ordinal()).isEqualTo(1);
            assertThat(r.statistics().chunkCount()).isGreaterThan(2);
            assertThat(r.statistics().maxEstimatedTokens()).isLessThanOrEqualTo(64);
        });
        verify(documents).get("alice", doc.id());
        when(documents.get("bob", doc.id())).thenThrow(new RagFailure(RagFailure.Kind.NOT_FOUND, "Документ не найден."));
        assertThatThrownBy(() -> service.preview("bob", doc.id(), 0, 20)).hasMessage("Документ не найден.");
        assertThatThrownBy(() -> service.preview("alice", doc.id(), 0, 101)).isInstanceOf(RagFailure.class);
        assertThat(service.preview("alice", doc.id(), Integer.MAX_VALUE, 20).results()).allSatisfy(r -> assertThat(r.chunks()).isEmpty());
    }

    private RagDocument document(String text, String format, List<ExtractedText.Block> blocks) {
        return new RagDocument(UUID.randomUUID(), "alice", "test.docx", "application/octet-stream", UUID.randomUUID(), 100,
                "a".repeat(64), new ExtractedText(text, new ExtractedText.Metadata(format, null, blocks, List.of())), Instant.now());
    }
}
