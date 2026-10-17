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
        // Raw diffs used to dominate a query whose first 96 tokens alone reached the cross-encoder.
        var original = CodeReviewServiceTest.request();
        var manyFiles = new ArrayList<com.github.vladsaraykin.aichat.review.domain.ReviewRequest.ChangedFile>();
        for (int i = 0; i < 10; i++) manyFiles.add(new com.github.vladsaraykin.aichat.review.domain.ReviewRequest.ChangedFile(
                "src/Long" + i + ".java", "modified", "@@ -1 +1 @@\n-old\n+" + "x".repeat(1000), "context"));
        var large = new com.github.vladsaraykin.aichat.review.domain.ReviewRequest(UUID.randomUUID(), original.repository(),
                original.pullNumber(), original.baseSha(), original.headSha(), original.title(), manyFiles);
        clearInvocations(embeddings, reranker);
        assertThat(service.retrieve("vlad", large)).containsExactly(CodeReviewServiceTest.SOURCE);
        var embeddingQuery = org.mockito.ArgumentCaptor.forClass(String.class);
        var rankingQuery = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(embeddings, times(3)).embed(embeddingQuery.capture());
        verify(reranker, times(3)).score(rankingQuery.capture(), anyList());
        assertThat(rankingQuery.getAllValues()).isEqualTo(embeddingQuery.getAllValues());
        assertThat(rankingQuery.getAllValues()).allSatisfy(q -> assertThat(q).hasSizeLessThan(300).doesNotContain("xxx", "@@"));
        assertThat(rankingQuery.getAllValues().getFirst()).contains("Long0, Long1, Long2, Long3");
        assertThat(rankingQuery.getAllValues().get(1)).contains("Long4, Long5, Long6, Long7").doesNotContain("Long8");
        assertThat(rankingQuery.getAllValues().getLast()).contains(original.title());
        // The same chunk from several searches is included only once, with its best valid score.
        when(reranker.score(anyString(), anyList())).thenReturn(List.of(.01), List.of(.9), List.of(.1));
        assertThat(service.retrieve("vlad", large)).containsExactly(CodeReviewServiceTest.SOURCE);
        when(reranker.score(anyString(), anyList())).thenReturn(List.of(.01));
        assertThatThrownBy(() -> service.retrieve("vlad", large)).hasMessage("project_context_empty");
        when(reranker.score(anyString(), anyList())).thenReturn(List.of(.9));
        var tinyBudget = new ReviewContextService(manifest, repository, embeddings, props, reranker,
                new RerankerSettings(URI.create("http://localhost:8000"), 10, 20, 5, .2), new RagSourceSelector(String::length, 1));
        assertThatThrownBy(() -> tinyBudget.retrieve("vlad", large)).hasMessage("project_context_empty");
        // A failure in a later component/topic query must not fall back to earlier accepted evidence.
        when(reranker.score(anyString(), anyList())).thenReturn(List.of(.9), List.of(Double.NaN));
        assertThatThrownBy(() -> service.retrieve("vlad", large)).isInstanceOf(Reranker.Failure.class);
        when(reranker.score(anyString(), anyList())).thenReturn(List.of(Double.NaN));
        assertThatThrownBy(() -> service.retrieve("vlad", CodeReviewServiceTest.request())).isInstanceOf(Reranker.Failure.class);
        assertThatThrownBy(() -> service.retrieve("other", CodeReviewServiceTest.request())).hasMessage("project_index_empty");
        verify(manifest).activeDocumentIds("other");
    }
}
