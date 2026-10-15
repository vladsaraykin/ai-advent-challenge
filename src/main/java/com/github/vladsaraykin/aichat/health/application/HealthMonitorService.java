package com.github.vladsaraykin.aichat.health.application;

import com.github.vladsaraykin.aichat.mcp.application.McpCatalogService;
import com.github.vladsaraykin.aichat.rag.infrastructure.RagProperties;
import com.github.vladsaraykin.aichat.rag.infrastructure.RerankerSettings;
import com.github.vladsaraykin.aichat.mcp.domain.McpServerView;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Executes small, bounded probes and returns only sanitized operational status. */
@Service
public class HealthMonitorService {
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_RESPONSE_BYTES = 1_000_000;

    private final DataSource dataSource;
    private final RagProperties rag;
    private final URI cloudModelsUri;
    private final String cloudApiKey;
    private final ObjectProvider<RerankerSettings> rerankerSettings;
    private final McpCatalogService mcpCatalog;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final java.util.concurrent.ExecutorService checks = Executors.newFixedThreadPool(6, task -> {
        var thread = new Thread(task, "health-check");
        thread.setDaemon(true);
        return thread;
    });

    @org.springframework.beans.factory.annotation.Autowired
    public HealthMonitorService(DataSource dataSource, RagProperties rag,
            @Value("${spring.ai.openai.base-url:https://api.openai.com/v1}") URI cloudBaseUrl,
            @Value("${spring.ai.openai.api-key:missing-api-key}") String cloudApiKey,
            ObjectProvider<RerankerSettings> rerankerSettings, McpCatalogService mcpCatalog) {
        this(dataSource, rag, cloudBaseUrl, cloudApiKey, rerankerSettings, mcpCatalog,
                HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    HealthMonitorService(DataSource dataSource, RagProperties rag,
            URI cloudBaseUrl, String cloudApiKey, ObjectProvider<RerankerSettings> rerankerSettings,
            McpCatalogService mcpCatalog, HttpClient http) {
        this.dataSource = dataSource;
        this.rag = rag;
        this.cloudModelsUri = appendPath(cloudBaseUrl, "models");
        this.cloudApiKey = cloudApiKey;
        this.rerankerSettings = rerankerSettings;
        this.mcpCatalog = mcpCatalog;
        this.http = http;
    }

    public Report check() {
        var definitions = List.<Probe>of(
                new Probe("postgresql", "PostgreSQL", this::checkDatabase),
                new Probe("openai", "OpenAI API", this::checkCloud),
                new Probe("ollama", "Ollama · embeddings", this::checkOllama),
                new Probe("reranker", "Reranker", this::checkReranker),
                new Probe("mcp", "MCP-серверы", this::checkMcp));
        var futures = definitions.stream().map(probe -> checks.submit(() -> probe(probe))).toList();
        var results = new ArrayList<Check>();
        for (int i = 0; i < futures.size(); i++) results.add(await(definitions.get(i), futures.get(i)));
        String overall = results.stream().anyMatch(check -> check.status().equals("DOWN")) ? "DEGRADED"
                : results.stream().anyMatch(check -> check.status().equals("UP")) ? "UP" : "UNKNOWN";
        return new Report(overall, Instant.now(), List.copyOf(results));
    }

    private ProbeResult checkDatabase() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(3);
            statement.execute("SELECT 1");
            return ProbeResult.up("Соединение и SELECT 1 успешны");
        } catch (Exception error) {
            return ProbeResult.down("Нет соединения с PostgreSQL");
        }
    }

    private ProbeResult checkCloud() {
        if (cloudApiKey == null || cloudApiKey.isBlank() || cloudApiKey.equals("missing-api-key")) {
            return ProbeResult.notConfigured("API-ключ OpenAI не настроен");
        }
        return get(cloudModelsUri, cloudApiKey, "OpenAI API доступен");
    }

    private ProbeResult checkOllama() {
        var result = get(appendPath(rag.ollama().baseUrl(), "api/tags"), null, "Ollama доступна");
        if (!result.status().equals("UP")) return result;
        try {
            var response = json.readTree(result.body());
            var models = response.path("models");
            for (var model : models) {
                String name = model.path("name").asString("");
                String modelName = name.contains(":") ? name.substring(0, name.indexOf(':')) : name;
                if (name.equals(rag.ollama().model()) || modelName.equals(rag.ollama().model())) {
                    return ProbeResult.up("Ollama доступна; embedding-модель найдена");
                }
            }
            return ProbeResult.down("Ollama доступна, но настроенная embedding-модель не найдена");
        } catch (RuntimeException error) {
            return ProbeResult.down("Ollama вернула некорректный ответ");
        }
    }

    private ProbeResult checkReranker() {
        var settings = rerankerSettings.getIfAvailable();
        if (settings == null) return ProbeResult.disabled("RAG отключён");
        return get(appendPath(settings.baseUrl(), "health"), null, "Reranker доступен");
    }

    private ProbeResult checkMcp() {
        List<McpServerView> servers = mcpCatalog.servers();
        if (servers.isEmpty()) return ProbeResult.notConfigured("MCP-серверы не настроены");
        long connected = servers.stream().filter(server -> server.connected() && server.error() == null).count();
        if (connected == servers.size()) return ProbeResult.up("Подключены MCP-серверы: " + connected + " из " + servers.size());
        if (connected == 0) return ProbeResult.down("Нет доступных MCP-серверов (0 из " + servers.size() + ")");
        return ProbeResult.down("Доступны не все MCP-серверы: " + connected + " из " + servers.size());
    }

    private ProbeResult get(URI uri, String bearerToken, String successMessage) {
        try {
            var builder = HttpRequest.newBuilder(uri).timeout(HTTP_TIMEOUT).GET();
            if (bearerToken != null) builder.header("Authorization", "Bearer " + bearerToken);
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.body().length() > MAX_RESPONSE_BYTES) return ProbeResult.down("Сервис вернул слишком большой ответ");
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new ProbeResult("UP", successMessage, response.body());
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return ProbeResult.down("Сервис отклонил авторизацию");
            }
            return ProbeResult.down("Сервис вернул HTTP " + response.statusCode());
        } catch (Exception error) {
            return ProbeResult.down("Сервис недоступен или не ответил за 3 секунды");
        }
    }

    private Check probe(Probe definition) {
        long started = System.nanoTime();
        try {
            var result = definition.action().get();
            return new Check(definition.id(), definition.name(), result.status(), result.message(), (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException error) {
            return new Check(definition.id(), definition.name(), "DOWN", "Проверка завершилась ошибкой", (System.nanoTime() - started) / 1_000_000);
        }
    }

    private Check await(Probe definition, Future<Check> future) {
        try { return future.get(5, TimeUnit.SECONDS); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt(); future.cancel(true);
            return new Check(definition.id(), definition.name(), "DOWN", "Проверка прервана", 0);
        } catch (Exception error) {
            future.cancel(true);
            return new Check(definition.id(), definition.name(), "DOWN", "Проверка не завершилась за 5 секунд", 5000);
        }
    }

    @jakarta.annotation.PreDestroy
    public void close() { checks.shutdownNow(); http.shutdownNow(); }

    private static URI appendPath(URI baseUrl, String path) {
        String prefix = baseUrl.toString().replaceAll("/+$", "");
        return URI.create(prefix + "/" + path);
    }

    public record Report(String status, Instant checkedAt, List<Check> checks) { }
    public record Check(String id, String name, String status, String message, long durationMs) { }
    private record Probe(String id, String name, java.util.function.Supplier<ProbeResult> action) { }
    private record ProbeResult(String status, String message, String body) {
        static ProbeResult up(String message) { return new ProbeResult("UP", message, ""); }
        static ProbeResult down(String message) { return new ProbeResult("DOWN", message, ""); }
        static ProbeResult disabled(String message) { return new ProbeResult("DISABLED", message, ""); }
        static ProbeResult notConfigured(String message) { return new ProbeResult("NOT_CONFIGURED", message, ""); }
    }
}
