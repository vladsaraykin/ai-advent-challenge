package com.github.vladsaraykin.aichat.rag;

import com.github.vladsaraykin.aichat.rag.application.*;
import com.github.vladsaraykin.aichat.rag.domain.*;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentServiceTest {
    private final DocumentRepository repository = mock(DocumentRepository.class);
    private final DocumentStore store = mock(DocumentStore.class);
    private final DocumentExtractor extractor = mock(DocumentExtractor.class);
    private final DocumentService service = new DocumentService(repository, store, extractor);
    private final DocumentStore.Stored file = new DocumentStore.Stored(UUID.randomUUID(), Path.of("test"), 100, "a".repeat(64));
    private final ExtractedText text = new ExtractedText("Текст 😀", new ExtractedText.Metadata("pdf", 1, List.of(), List.of()));

    @Test void savesAuthenticatedOwnerAndDoesNotDiscardOnUncertainDatabaseFailure() {
        when(store.save(any())).thenReturn(file); when(extractor.extract(any(), any())).thenReturn(text);
        var result = service.upload("alice", "demo.PDF", InputStream.nullInputStream());
        assertThat(result.owner()).isEqualTo("alice"); assertThat(result.summary().characterCount()).isEqualTo(7);
        verify(repository).insert(result);
        doThrow(RagFailure.storage()).when(repository).insert(any());
        assertThatThrownBy(() -> service.upload("alice", "demo.pdf", InputStream.nullInputStream())).isInstanceOf(RagFailure.class);
        verify(store, never()).discard(any());
    }

    @Test void invalidUploadDoesNotPersistAndReleasesSlotForRetry() {
        when(store.save(any())).thenReturn(file);
        when(extractor.extract(any(), any())).thenThrow(new RagFailure(RagFailure.Kind.NO_TEXT, "Нет текста"));
        assertThatThrownBy(() -> service.upload("alice", "demo.pdf", InputStream.nullInputStream())).hasMessage("Нет текста");
        verify(store).discard(file); verify(repository, never()).insert(any());
        doReturn(text).when(extractor).extract(any(), any());
        assertThat(service.upload("alice", "demo.pdf", InputStream.nullInputStream()).owner()).isEqualTo("alice");
    }

    @Test void pathTraversalAndUnsupportedFilesAreRejectedBeforeStorage() {
        for (String filename : List.of("../a.pdf", "C:\\a.doc", "a.txt", "a\u0000.pdf")) {
            assertThatThrownBy(() -> service.upload("alice", filename, InputStream.nullInputStream())).isInstanceOf(RagFailure.class);
        }
        verifyNoInteractions(store, extractor, repository);
    }

    @Test void listAndReadAlwaysUseOwnerAndBoundPagination() {
        UUID id = UUID.randomUUID();
        when(repository.find("bob", id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("bob", id)).hasMessage("Документ не найден.");
        service.list("alice", 20, 0); verify(repository).list("alice", 20, 0);
        assertThatThrownBy(() -> service.list("alice", 1000, 0)).isInstanceOf(RagFailure.class);
        verify(repository, never()).list("alice", 1000, 0);
    }

    @Test void concurrentExtractionIsRejectedWithoutQueueing() throws Exception {
        when(store.save(any())).thenReturn(file);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(extractor.extract(any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return text;
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.upload("alice", "a.pdf", InputStream.nullInputStream()));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.upload("bob", "b.pdf", InputStream.nullInputStream()))
                        .isInstanceOfSatisfying(RagFailure.class, error -> assertThat(error.kind()).isEqualTo(RagFailure.Kind.BUSY));
            } finally { release.countDown(); }
            assertThat(first.get(5, TimeUnit.SECONDS).owner()).isEqualTo("alice");
        }
    }
}
