package com.github.vladsaraykin.aichat.health.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.github.vladsaraykin.aichat.mcp.application.McpCatalogService;
import com.github.vladsaraykin.aichat.rag.infrastructure.RagProperties;
import com.github.vladsaraykin.aichat.rag.infrastructure.RerankerSettings;
import com.github.vladsaraykin.aichat.mcp.domain.McpServerView;
import com.github.vladsaraykin.aichat.mcp.domain.McpToolView;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class HealthMonitorServiceTest {
    private String baseUrl;
    private HttpClient http;

    @BeforeEach
    void startHttpServer() throws Exception {
        baseUrl = "http://health-test.invalid";
        http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            var response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn(request.uri().getPath().endsWith("/api/tags")
                    ? "{\"models\":[{\"name\":\"embeddinggemma:latest\"}]}" : "{\"data\":[]}");
            return response;
        });
    }

    @AfterEach
    void stopHttpServer() { }

    @Test
    void reportsSuccessfulDependencyChecksWithoutReturningSecretsOrBodies() throws Exception {
        var dataSource = mock(DataSource.class);
        var connection = mock(java.sql.Connection.class);
        var statement = mock(java.sql.Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        var rag = new RagProperties(
                new RagProperties.Database("jdbc:postgresql://localhost/db", "user", "secret", 5, Duration.ofSeconds(3)),
                new RagProperties.Ollama(URI.create(baseUrl), "embeddinggemma", 768,
                        Duration.ofSeconds(1), Duration.ofSeconds(3)));
        @SuppressWarnings("unchecked") ObjectProvider<RerankerSettings> reranker = mock(ObjectProvider.class);
        when(reranker.getIfAvailable()).thenReturn(new RerankerSettings(URI.create(baseUrl), 10, 20, 5, .2));
        var mcp = mock(McpCatalogService.class);
        when(mcp.servers()).thenReturn(List.of(new McpServerView("filesystem", "Filesystem", "1", "1",
                true, null, List.<McpToolView>of())));

        var report = new HealthMonitorService(dataSource, rag, URI.create(baseUrl + "/v1"),
                "private-api-key", reranker, mcp, http).check();

        assertThat(report.status()).isEqualTo("UP");
        assertThat(report.checks()).extracting(HealthMonitorService.Check::status)
                .containsExactly("UP", "UP", "UP", "UP", "UP");
        assertThat(report.checks()).extracting(HealthMonitorService.Check::message)
                .contains("Ollama доступна; embedding-модель найдена", "Reranker доступен");
        assertThat(report.toString()).doesNotContain("private-api-key", "secret", "embeddinggemma:latest");
        verify(statement).execute("SELECT 1");
    }

}
