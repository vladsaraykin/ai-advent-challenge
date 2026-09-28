package com.github.vladsaraykin.aichat.rag;

import com.github.vladsaraykin.aichat.rag.application.*;
import com.github.vladsaraykin.aichat.rag.domain.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IndexingServiceTest {
    private final DocumentService documents = mock(DocumentService.class);
    private final IndexRepository repository = mock(IndexRepository.class);
    private final ChunkingSettings settings = new ChunkingSettings(64, 0);
    private final RagDocument document = new RagDocument(UUID.randomUUID(), "alice", "test.pdf", "application/pdf",
            UUID.randomUUID(), 10, "a".repeat(64), new ExtractedText("Текст документа",
            new ExtractedText.Metadata("pdf", 1, List.of(), List.of())), Instant.now());

    @Test void savesInOrderRetriesAsSeparateRunAndPreservesOwnership() throws Exception {
        when(documents.get("alice", document.id())).thenReturn(document);
        when(repository.create(any(), any(), any(), any())).thenAnswer(call -> UUID.randomUUID());
        var finished = new CountDownLatch(2);
        doAnswer(call -> { finished.countDown(); return null; }).when(repository).finish(eq("alice"), any(), anyLong(), any());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        EmbeddingModel model = text -> {
            if (calls.incrementAndGet() == 1) throw new EmbeddingModel.Failure("OLLAMA_INPUT_REJECTED");
            return new EmbeddingModel.Result(new float[768], 7L);
        };
        try (var service = service(model)) {
            service.start("alice", document.id(), DocumentChunk.Strategy.FIXED_SIZE);
            service.start("alice", document.id(), DocumentChunk.Strategy.FIXED_SIZE);
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            verify(repository).finish(eq("alice"), any(), anyLong(), eq("OLLAMA_INPUT_REJECTED"));
            verify(repository).finish(eq("alice"), any(), anyLong(), isNull());
            verify(repository, times(2)).prepare(eq("alice"), any(), argThat(chunks -> chunks.size() == 1));
            verify(repository).saveEmbedding(eq("alice"), any(), eq(0), any());
            verify(repository).recoverInterrupted();
            when(documents.get("bob", document.id())).thenThrow(new RagFailure(RagFailure.Kind.NOT_FOUND, "Missing"));
            assertThatThrownBy(() -> service.start("bob", document.id(), DocumentChunk.Strategy.STRUCTURAL)).isInstanceOf(RagFailure.class);
            verify(repository, never()).create(argThat(doc -> doc.owner().equals("bob")), any(), any(), any());
        }
    }

    @Test void boundsQueueAndInterruptsWorkerOnShutdown() throws Exception {
        when(documents.get("alice", document.id())).thenReturn(document);
        when(repository.create(any(), any(), any(), any())).thenAnswer(call -> UUID.randomUUID());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var service = service(text -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new EmbeddingModel.Failure("INTERRUPTED"); }
            return new EmbeddingModel.Result(new float[768], null);
        })) {
            for (int i = 0; i < 3; i++) service.start("alice", document.id(), DocumentChunk.Strategy.FIXED_SIZE);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.start("alice", document.id(), DocumentChunk.Strategy.FIXED_SIZE))
                    .isInstanceOfSatisfying(RagFailure.class, error -> assertThat(error.kind()).isEqualTo(RagFailure.Kind.BUSY));
        }
        verify(repository).finish(eq("alice"), any(), anyLong(), eq("INTERRUPTED"));
    }

    private IndexingService service(EmbeddingModel model) {
        return new IndexingService(documents, new DocumentChunker(String::length, settings), settings, repository, model, "embeddinggemma");
    }
}
