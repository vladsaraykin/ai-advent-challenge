package com.github.vladsaraykin.aichat.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

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
    void routesDependentCallsToTwoSelectedServersOnly() {
        var expenses = client("expenses", "summarize");
        var filesystem = client("filesystem", "write_file");
        var other = client("other", "delete_all");
        when(expenses.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("report-data")), false, null, Map.of()));
        when(filesystem.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("saved")), false, null, Map.of()));
        var callbacks = new McpToolService(provider(List.of(expenses, filesystem, other)))
                .callbacks(List.of("filesystem", "expenses", "expenses"));
        assertThat(callbacks).extracting(value -> value.getToolDefinition().name())
                .containsExactly("summarize", "write_file");
        String report = callbacks.getFirst().call("{}");
        assertThat(report).contains("report-data");
        callbacks.getLast().call("{\"content\":\"report-data\"}");
        var order = org.mockito.Mockito.inOrder(expenses, filesystem);
        order.verify(expenses).callTool(any());
        order.verify(filesystem).callTool(org.mockito.ArgumentMatchers.argThat(request ->
                request.name().equals("write_file") && request.arguments().get("content").equals("report-data")));
        verify(other, never()).callTool(any());
        verify(other, never()).listTools();
    }

    @Test
    void rejectsDuplicateToolNamesAndUnknownSelections() {
        var service = new McpToolService(provider(List.of(client("one", "read"), client("two", "read"))));
        assertThatThrownBy(() -> service.callbacks(List.of("one", "two")))
                .isInstanceOf(ChatFailure.class).hasMessageContaining("совпадают");
        assertThatThrownBy(() -> service.callbacks(List.of("one", "missing")))
                .isInstanceOf(ChatFailure.class).hasMessageContaining("недоступен");
        assertThat(service.callbacks(List.of())).isEmpty();
    }

    @Test
    void limitsCallsAcrossServersPerRequest() {
        var first = client("one", "read");
        var second = client("two", "write");
        when(first.callTool(any())).thenReturn(new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("ok")), false, null, Map.of()));
        when(second.callTool(any())).thenReturn(new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("ok")), false, null, Map.of()));
        var service = new McpToolService(provider(List.of(first, second)));
        var callbacks = service.callbacks(List.of("one", "two"));
        for (int i = 0; i < 24; i++) callbacks.get(i % 2).call("{}");
        assertThatThrownBy(() -> callbacks.getFirst().call("{}"))
                .isInstanceOf(ChatFailure.class).hasMessageContaining("24");
        assertThat(service.callbacks(List.of("one")).getFirst().call("{}")).contains("ok");
    }

    @Test
    void resolvesOnlyTheExplicitlySelectedConnection() {
        var first = client("first", "first_tool");
        var second = client("custom-tools", "lookup_order");

        var callbacks = new McpToolService(provider(List.of(first, second))).callbacks(List.of("custom-tools"));

        assertThat(callbacks).extracting(value -> value.getToolDefinition().name())
                .containsExactly("lookup_order");
    }

    @Test
    void rejectsUnknownConnectionWithoutFallingBackToAnotherServer() {
        var service = new McpToolService(provider(List.of(client("filesystem", "read_file"))));

        assertThatThrownBy(() -> service.callbacks(List.of("missing")))
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
