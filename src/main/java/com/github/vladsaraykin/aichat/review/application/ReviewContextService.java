package com.github.vladsaraykin.aichat.review.application;

import com.github.vladsaraykin.aichat.rag.application.*;
import com.github.vladsaraykin.aichat.rag.domain.*;
import com.github.vladsaraykin.aichat.rag.infrastructure.*;
import com.github.vladsaraykin.aichat.review.domain.ReviewRequest;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Uses only the authenticated owner's managed project corpus, never unrelated uploaded documents. */
public class ReviewContextService {
    private static final Logger log = LoggerFactory.getLogger(ReviewContextService.class);
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
        var queries = queries(request);
        var byChunk = new LinkedHashMap<UUID, RagQuestion.Source>();
        var bestScores = new HashMap<UUID, Double>();
        for (String query : queries) {
            var vector = embeddings.embed(query).vector();
            var found = repository.searchDocuments(owner, properties.ollama().model(), vector, ranking.candidateK(), ids);
            if (found.isEmpty()) continue;
            var scores = reranker.score(query, found.stream().map(s -> s.chunk().content()).toList());
            if (scores.size() != found.size() || scores.stream().anyMatch(s -> s == null || !Double.isFinite(s) || s < 0 || s > 1))
                throw new Reranker.Failure();
            for (int i = 0; i < found.size(); i++) {
                var source = found.get(i);
                var id = source.chunk().chunkId();
                if (!bestScores.containsKey(id) || scores.get(i) > bestScores.get(id)) {
                    byChunk.put(id, source);
                    bestScores.put(id, scores.get(i));
                }
            }
        }
        var candidates = List.copyOf(byChunk.values());
        if (candidates.isEmpty()) throw new IllegalStateException("project_index_empty");
        var scores = candidates.stream().map(s -> bestScores.get(s.chunk().chunkId())).toList();
        var selection = selector.rank(candidates, scores, true,
                new RetrievalOptions(ranking.candidateK(), ranking.finalK(), ranking.threshold()), ranking.finalK());
        var selected = selection.sources();
        var decisions = selection.candidates().stream().collect(java.util.stream.Collectors.groupingBy(
                RagQuestion.Candidate::decision, TreeMap::new, java.util.stream.Collectors.counting()));
        log.info("review_retrieval_completed requestId={} queryCount={} documentCount={} candidateCount={} selectedCount={} maxScore={} threshold={} decisions={}",
                request.requestId(), queries.size(), ids.size(), candidates.size(), selected.size(),
                scores.stream().mapToDouble(Double::doubleValue).max().orElse(0), ranking.threshold(), decisions);
        if (selected.isEmpty()) throw new IllegalStateException("project_context_empty");
        return selected;
    }

    // The deployed cross-encoder retains only 96 query tokens. Do not send raw diff/imports:
    // they drown out the topic and favour the first file. At most three bounded queries.
    private static List<String> queries(ReviewRequest request) {
        var result = new ArrayList<String>();
        var components = request.files().stream().map(ReviewRequest.ChangedFile::path)
                .filter(p -> p.matches(".*\\.(java|jsx|js|tsx|ts)$"))
                .filter(p -> !p.contains("/test/") && !p.matches(".*[.]((test)|(spec))[.].*"))
                .sorted(Comparator.comparingInt((String p) -> p.startsWith("src/main/") ? 0 : 1))
                .map(p -> p.substring(p.lastIndexOf('/') + 1).replaceFirst("[.][^.]+$", ""))
                .map(p -> p.substring(0, Math.min(40, p.length())))
                .distinct().limit(8).toList();
        for (int i = 0; i < components.size(); i += 4)
            result.add("How are " + String.join(", ", components.subList(i, Math.min(i + 4, components.size())))
                    + " implemented in this project?");
        String topic = request.title().strip().replaceAll("\\s+", " ");
        result.add("Project documentation relevant to: " + topic.substring(0, Math.min(240, topic.length())));
        return List.copyOf(result);
    }
}
