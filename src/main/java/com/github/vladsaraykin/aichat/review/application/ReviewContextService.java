package com.github.vladsaraykin.aichat.review.application;

import com.github.vladsaraykin.aichat.rag.application.*;
import com.github.vladsaraykin.aichat.rag.domain.*;
import com.github.vladsaraykin.aichat.rag.infrastructure.*;
import com.github.vladsaraykin.aichat.review.domain.ReviewRequest;
import java.util.List;

/** Uses only the authenticated owner's managed project corpus, never unrelated uploaded documents. */
public class ReviewContextService {
    private final ProjectDocumentSyncRepository manifest;
    private final RagQuestionRepository repository;
    private final EmbeddingModel embeddings;
    private final RagProperties properties;
    private final Reranker reranker;
    private final RerankerSettings ranking;
    private final RagSourceSelector selector;

    public ReviewContextService(ProjectDocumentSyncRepository manifest, RagQuestionRepository repository,
            EmbeddingModel embeddings, RagProperties properties, Reranker reranker,
            RerankerSettings ranking, RagSourceSelector selector) {
        this.manifest = manifest; this.repository = repository; this.embeddings = embeddings;
        this.properties = properties; this.reranker = reranker; this.ranking = ranking; this.selector = selector;
    }
    public List<RagQuestion.Source> retrieve(String owner, ReviewRequest request) {
        var ids = manifest.activeDocumentIds(owner);
        if (ids.isEmpty()) throw new IllegalStateException("project_index_empty");
        String query = "Code review: architecture, security, persistence, API contracts. " + request.title() + "\n"
                + request.files().stream().map(f -> f.path() + "\n" + f.patch().substring(0, Math.min(1000, f.patch().length())))
                    .collect(java.util.stream.Collectors.joining("\n"));
        query = query.substring(0, Math.min(6000, query.length()));
        var vector = embeddings.embed(query).vector();
        var candidates = repository.searchDocuments(owner, properties.ollama().model(), vector, ranking.candidateK(), ids);
        if (candidates.isEmpty()) throw new IllegalStateException("project_index_empty");
        var scores = reranker.score(query, candidates.stream().map(s -> s.chunk().content()).toList());
        if (scores.size() != candidates.size() || scores.stream().anyMatch(s -> s == null || !Double.isFinite(s) || s < 0 || s > 1))
            throw new Reranker.Failure();
        var selected = selector.rank(candidates, scores, true,
                new RetrievalOptions(ranking.candidateK(), ranking.finalK(), ranking.threshold()), ranking.finalK()).sources();
        if (selected.isEmpty()) throw new IllegalStateException("project_context_empty");
        return selected;
    }
}
