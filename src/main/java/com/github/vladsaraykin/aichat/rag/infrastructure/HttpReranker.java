package com.github.vladsaraykin.aichat.rag.infrastructure;

import com.github.vladsaraykin.aichat.rag.application.Reranker;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public final class HttpReranker implements Reranker, AutoCloseable {
    private final RerankerSettings settings;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().build();
    public HttpReranker(RerankerSettings settings) { this.settings = settings; }
    @Override public List<Double> score(String question, List<String> documents) {
        if (documents.isEmpty()) return List.of();
        if (documents.size() > 50) throw new Failure();
        try {
            var request = HttpRequest.newBuilder(settings.baseUrl().resolve("/rerank"))
                    .timeout(Duration.ofSeconds(settings.timeoutSeconds())).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", question, "documents", documents)))).build();
            // Complete-body handler keeps the request timeout active through body reception.
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200 || response.body().length()>16000) throw new Failure();
            var scores = json.readTree(response.body()).path("scores");
            if (!scores.isArray() || scores.size() != documents.size()) throw new Failure();
            var result = new ArrayList<Double>();
            for (var score : scores) {
                double value = score.asDouble();
                if (!score.isNumber() || !Double.isFinite(value) || value < 0 || value > 1) throw new Failure();
                result.add(value);
            }
            return List.copyOf(result);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new Failure(); }
        catch (Exception error) { throw new Failure(); }
    }
    @Override public void close() { client.shutdownNow(); }
}
