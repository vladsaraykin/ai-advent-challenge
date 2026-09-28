package com.github.vladsaraykin.aichat.rag.infrastructure;

import com.github.vladsaraykin.aichat.rag.application.EmbeddingModel;
import java.net.http.*;
import java.io.IOException;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** One chunk per request: bounded CPU/RAM usage on a small Ollama host. */
public final class OllamaEmbeddingModel implements EmbeddingModel, AutoCloseable {
    private final RagProperties.Ollama config;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    public OllamaEmbeddingModel(RagProperties.Ollama config) {
        this.config = config;
        this.client = HttpClient.newBuilder().connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public Result embed(String text) {
        var request = HttpRequest.newBuilder(config.baseUrl().resolve("/api/embed"))
                .timeout(config.requestTimeout()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("model", config.model(), "input", text, "truncate", false)))).build();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 429 || status >= 500) {
                    if (attempt < 2) { pause(attempt); continue; }
                    throw new Failure("OLLAMA_UNAVAILABLE");
                }
                if (status != 200) throw new Failure(status == 400 ? "OLLAMA_INPUT_REJECTED" : "OLLAMA_REQUEST_FAILED");
                var root = json.readTree(response.body());
                var embeddings = root.path("embeddings");
                if (!embeddings.isArray() || embeddings.size() != 1
                        || !embeddings.get(0).isArray() || embeddings.get(0).size() != config.dimensions()) {
                    throw new Failure("INVALID_EMBEDDING");
                }
                float[] vector = new float[config.dimensions()];
                for (int i = 0; i < vector.length; i++) {
                    var value = embeddings.get(0).get(i);
                    vector[i] = (float) value.asDouble();
                    if (!value.isNumber() || !Float.isFinite(vector[i])) throw new Failure("INVALID_EMBEDDING");
                }
                var usage = root.path("prompt_eval_count");
                if (!usage.isMissingNode() && (!usage.isIntegralNumber() || !usage.canConvertToLong() || usage.asLong() < 0)) {
                    throw new Failure("INVALID_EMBEDDING_USAGE");
                }
                return new Result(vector, usage.isMissingNode() ? null : usage.asLong());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Failure("INTERRUPTED");
            } catch (IOException e) {
                if (attempt == 2) throw new Failure("OLLAMA_UNAVAILABLE");
                pause(attempt);
            } catch (Failure e) { throw e;
            } catch (RuntimeException e) { throw new Failure("INVALID_EMBEDDING"); }
        }
        throw new Failure("OLLAMA_UNAVAILABLE");
    }

    private void pause(int attempt) {
        try { Thread.sleep(250L * (attempt + 1)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure("INTERRUPTED"); }
    }

    @Override public void close() { client.shutdownNow(); }
}
