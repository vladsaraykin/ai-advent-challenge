package com.github.vladsaraykin.aichat.rag.infrastructure;

import com.github.vladsaraykin.aichat.rag.application.EmbeddingModel;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OllamaEmbeddingModelTest {
    @Test void realHttpContractRetriesTransientFailureAndDisablesTruncation() throws Exception {
        var calls = new AtomicInteger();
        var payload = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            payload.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            int status = calls.incrementAndGet() == 1 ? 503 : 200;
            byte[] body = (status == 200 ? "{\"embeddings\":[[" + "0.1,".repeat(767)
                    + "0.1]],\"prompt_eval_count\":9}" : "private upstream details").getBytes();
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var result = client(server).embed("Текст документа");
            assertThat(result.vector()).hasSize(768);
            assertThat(result.promptTokens()).isEqualTo(9);
            assertThat(calls).hasValue(2);
            var request = JsonMapper.builder().build().readTree(payload.get());
            assertThat(request.path("truncate").asBoolean()).isFalse();
            assertThat(request.path("input").asText()).isEqualTo("Текст документа");
            assertThat(request.path("model").asText()).isEqualTo("embeddinggemma");
        } finally { server.stop(0); }
    }

    @Test void rejectsBadDimensionsAndSanitizesNonRetryableErrors() throws Exception {
        for (int status : new int[]{200, 400, 404, 500}) {
            var calls = new AtomicInteger();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/embed", exchange -> {
                calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
                byte[] body = (status == 200 ? "{\"embeddings\":[[1,2]]}" : "secret private text").getBytes();
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            });
            server.start();
            try {
                assertThatThrownBy(() -> client(server).embed("text"))
                        .isInstanceOf(EmbeddingModel.Failure.class).hasMessageNotContaining("secret");
                assertThat(calls).hasValue(status == 500 ? 3 : 1);
            } finally { server.stop(0); }
        }
    }

    private OllamaEmbeddingModel client(HttpServer server) {
        return new OllamaEmbeddingModel(new RagProperties.Ollama(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "embeddinggemma", 768,
                Duration.ofSeconds(2), Duration.ofSeconds(2)));
    }
}
