package com.github.vladsaraykin.aichat.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.vladsaraykin.aichat.agent.application.ChatFailure;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class McpToolServiceTest {

    @Test
    void resolvesOnlyTheExplicitlySelectedConnection() {
        var first = client("first", "first_tool");
        var second = client("custom-tools", "lookup_order");

        var callbacks = new McpToolService(provider(List.of(first, second))).callbacks("custom-tools");

        assertThat(callbacks).extracting(value -> value.getToolDefinition().name())
                .containsExactly("lookup_order");
    }

    @Test
    void rejectsUnknownConnectionWithoutFallingBackToAnotherServer() {
        var service = new McpToolService(provider(List.of(client("filesystem", "read_file"))));

        assertThatThrownBy(() -> service.callbacks("missing"))
                .isInstanceOf(ChatFailure.class)
                .hasMessage("Выбранный MCP-сервер недоступен. Обновите список подключений.");
    }

    private static McpSyncClient client(String id, String toolName) {
        var client = mock(McpSyncClient.class);
        var clientInfo = new McpSchema.Implementation("client", id, "1", null, List.of(), null);
        var serverInfo = new McpSchema.Implementation("server", id, "1", null, List.of(), null);
        when(client.getClientInfo()).thenReturn(clientInfo);
        when(client.getClientCapabilities()).thenReturn(McpSchema.ClientCapabilities.builder().build());
        when(client.getCurrentInitializationResult()).thenReturn(new McpSchema.InitializeResult("2025-11-25",
                McpSchema.ServerCapabilities.builder().tools(false).build(), serverInfo, null, Map.of()));
        when(client.isInitialized()).thenReturn(true);
        var tool = new McpSchema.Tool(toolName, toolName, "test tool", Map.of("type", "object"),
                null, null, Map.of(), List.of());
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool), null, Map.of()));
        return client;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static ObjectProvider<List<McpSyncClient>> provider(List<McpSyncClient> clients) {
        ObjectProvider<List<McpSyncClient>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any(Supplier.class))).thenReturn(clients);
        return provider;
    }
}
