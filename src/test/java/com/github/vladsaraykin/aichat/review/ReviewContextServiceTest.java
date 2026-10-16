package com.github.vladsaraykin.aichat.review;

import com.github.vladsaraykin.aichat.rag.application.*;
import com.github.vladsaraykin.aichat.rag.infrastructure.*;
import com.github.vladsaraykin.aichat.review.application.ReviewContextService;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewContextServiceTest {
    @Test void searchesOnlyOwnersManagedDocumentsAndFailsClosedOnRerankerFailure() {
        var manifest = mock(ProjectDocumentSyncRepository.class);
        var repository = mock(RagQuestionRepository.class);
        var embeddings = mock(EmbeddingModel.class);
        var reranker = mock(Reranker.class);
        var ids = List.of(UUID.randomUUID());
        when(manifest.activeDocumentIds("vlad")).thenReturn(ids);
        var vector = new float[768];
        when(embeddings.embed(anyString())).thenReturn(new EmbeddingModel.Result(vector, 1L));
        when(repository.searchDocuments(eq("vlad"), eq("embeddinggemma"), same(vector), eq(20), eq(ids)))
                .thenReturn(List.of(CodeReviewServiceTest.SOURCE));
        when(reranker.score(anyString(), anyList())).thenReturn(List.of(.9));
        var props = new RagProperties(new RagProperties.Database("jdbc:postgresql://localhost/test", "test", "test", 1, Duration.ofSeconds(1)),
                new RagProperties.Ollama(URI.create("http://localhost:11434"), "embeddinggemma", 768, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        var service = new ReviewContextService(manifest, repository, embeddings, props, reranker,
                new RerankerSettings(URI.create("http://localhost:8000"), 10, 20, 5, .2), new RagSourceSelector(String::length, 6000));
        assertThat(service.retrieve("vlad", CodeReviewServiceTest.request())).containsExactly(CodeReviewServiceTest.SOURCE);
        verify(repository, never()).searchAll(anyString(), anyString(), any(), anyInt());
        when(reranker.score(anyString(), anyList())).thenReturn(List.of(Double.NaN));
        assertThatThrownBy(() -> service.retrieve("vlad", CodeReviewServiceTest.request())).isInstanceOf(Reranker.Failure.class);
        assertThatThrownBy(() -> service.retrieve("other", CodeReviewServiceTest.request())).hasMessage("project_index_empty");
        verify(manifest).activeDocumentIds("other");
    }
}
