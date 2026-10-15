package com.github.vladsaraykin.aichat.rag;

import com.github.vladsaraykin.aichat.rag.application.DocumentService;
import com.github.vladsaraykin.aichat.rag.application.ProjectDocumentIndexer;
import com.github.vladsaraykin.aichat.rag.application.ProjectDocumentationSyncService;
import com.github.vladsaraykin.aichat.rag.domain.DocumentChunk;
import com.github.vladsaraykin.aichat.rag.domain.ExtractedText;
import com.github.vladsaraykin.aichat.rag.domain.IndexRun;
import com.github.vladsaraykin.aichat.rag.domain.RagDocument;
import com.github.vladsaraykin.aichat.rag.infrastructure.ProjectDocumentSyncRepository;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectDocumentationSyncServiceTest {
    @TempDir Path root;

    @Test void queuesOnlyAllowlistedMarkdownSources() throws Exception {
        Files.writeString(root.resolve("README.md"), "# Project");
        Files.createDirectories(root.resolve("project/docs"));
        Files.writeString(root.resolve("project/docs/api.md"), "# API");
        Files.writeString(root.resolve("ignored.txt"), "not indexed");

        var documents = mock(DocumentService.class);
        var indexing = mock(ProjectDocumentIndexer.class);
        var manifest = mock(ProjectDocumentSyncRepository.class);
        when(manifest.list("vlad")).thenReturn(List.of());
        when(documents.upload(eq("vlad"), anyString(), any(InputStream.class))).thenAnswer(call -> document());
        when(indexing.start(eq("vlad"), any(), eq(DocumentChunk.Strategy.STRUCTURAL))).thenAnswer(call -> run());

        new ProjectDocumentationSyncService(root, "vlad", documents, indexing, manifest).synchronize();

        verify(documents, times(2)).upload(eq("vlad"), contains(".md"), any(InputStream.class));
        verify(indexing, times(2)).start(eq("vlad"), any(), eq(DocumentChunk.Strategy.STRUCTURAL));
        verify(manifest, times(2)).pending(eq("vlad"), anyString(), anyString(), any(), any());
        verify(documents, never()).delete(anyString(), any());
    }

    @Test void scansTheConfiguredRepositoryRootAndQueuesProjectDocs() throws Exception {
        Path checkout = Path.of(".").toAbsolutePath().normalize();
        var documents = mock(DocumentService.class);
        var indexing = mock(ProjectDocumentIndexer.class);
        var manifest = mock(ProjectDocumentSyncRepository.class);
        when(manifest.list("vlad")).thenReturn(List.of());
        when(documents.upload(eq("vlad"), anyString(), any(InputStream.class))).thenAnswer(call -> document());
        when(indexing.start(eq("vlad"), any(), eq(DocumentChunk.Strategy.STRUCTURAL))).thenAnswer(call -> run());

        new ProjectDocumentationSyncService(checkout, "vlad", documents, indexing, manifest).synchronize();

        verify(documents, atLeastOnce()).upload(eq("vlad"), anyString(), any(InputStream.class));
        verify(documents, atLeastOnce()).upload(eq("vlad"), contains("application-configuration.md"), any(InputStream.class));
        verify(manifest, atLeastOnce()).pending(eq("vlad"), anyString(), anyString(), any(), any());
    }

    @Test void failedSourceScanDoesNotDeletePreviouslyManagedDocuments() throws Exception {
        var documents = mock(DocumentService.class);
        var indexing = mock(ProjectDocumentIndexer.class);
        var manifest = mock(ProjectDocumentSyncRepository.class);
        var existing = new ProjectDocumentSyncRepository.Entry("README.md", "abc", UUID.randomUUID(), null, null, null);
        when(manifest.list("vlad")).thenReturn(List.of(existing));

        new ProjectDocumentationSyncService(root, "vlad", documents, indexing, manifest).synchronize();

        verify(manifest, never()).remove(anyString(), anyString());
        verify(documents, never()).delete(anyString(), any());
    }

    private static RagDocument document() {
        return new RagDocument(UUID.randomUUID(), "vlad", "project.md", "text/markdown; charset=utf-8",
                UUID.randomUUID(), 8, "a".repeat(64), new ExtractedText("# Project",
                new ExtractedText.Metadata("markdown", null, List.of(), List.of())), Instant.now());
    }
    private static IndexRun run() {
        return new IndexRun(UUID.randomUUID(), UUID.randomUUID(), DocumentChunk.Strategy.STRUCTURAL,
                "PENDING", "embeddinggemma", 768, 0, 0, 0L, null, null, Instant.now(), null);
    }
}
