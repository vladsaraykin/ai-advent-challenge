package com.github.vladsaraykin.aichat.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class McpCatalogServiceTest {

    @Test
    void returnsInitializedServerAndAllToolPages() {
        var client = mock(McpSyncClient.class);
        var clientInfo = implementation("ai-advent-client", "filesystem", "1.0.0");
        var serverInfo = implementation("filesystem", "Filesystem MCP Server", "2026.7.4");
        var initialization = new McpSchema.InitializeResult("2025-11-25",
                McpSchema.ServerCapabilities.builder().tools(false).build(), serverInfo, null, Map.of());
        when(client.getClientInfo()).thenReturn(clientInfo);
        when(client.getServerInfo()).thenReturn(serverInfo);
        when(client.getCurrentInitializationResult()).thenReturn(initialization);
        when(client.isInitialized()).thenReturn(true);

        var read = new McpSchema.Tool("read_text_file", "Read text file", "Reads a UTF-8 text file",
                Map.of("type", "object"), null,
                new McpSchema.ToolAnnotations(null, true, false, true, false, null), Map.of(), List.of());
        var write = new McpSchema.Tool("write_file", "Write file", "Writes a file",
                Map.of("type", "object"), null,
                new McpSchema.ToolAnnotations(null, false, true, false, false, null), Map.of(), List.of());
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(read), "next", Map.of()));
        when(client.listTools("next")).thenReturn(new McpSchema.ListToolsResult(List.of(write), null, Map.of()));

        var result = new McpCatalogService(provider(List.of(client))).servers();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo("filesystem");
        assertThat(result.getFirst().name()).isEqualTo("Filesystem MCP Server");
        assertThat(result.getFirst().protocolVersion()).isEqualTo("2025-11-25");
        assertThat(result.getFirst().connected()).isTrue();
        assertThat(result.getFirst().tools()).extracting("name")
                .containsExactly("read_text_file", "write_file");
        assertThat(result.getFirst().tools().getFirst().readOnly()).isTrue();
        assertThat(result.getFirst().tools().get(1).destructive()).isTrue();
    }

    @Test
    void sanitizesToolDiscoveryFailure() {
        var client = mock(McpSyncClient.class);
        when(client.getClientInfo()).thenReturn(implementation("client", "filesystem", "1"));
        when(client.getServerInfo()).thenReturn(implementation("filesystem", "Filesystem", "1"));
        when(client.listTools()).thenThrow(new IllegalStateException("raw server details"));

        var result = new McpCatalogService(provider(List.of(client))).servers().getFirst();

        assertThat(result.connected()).isFalse();
        assertThat(result.error()).isEqualTo("Не удалось получить список инструментов от MCP-сервера.");
        assertThat(result.error()).doesNotContain("raw server details");
        assertThat(result.tools()).isEmpty();
    }

    private static McpSchema.Implementation implementation(String name, String title, String version) {
        return new McpSchema.Implementation(name, title, version, null, List.of(), null);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static ObjectProvider<List<McpSyncClient>> provider(List<McpSyncClient> clients) {
        ObjectProvider<List<McpSyncClient>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any(Supplier.class))).thenReturn(clients);
        return provider;
    }
}
