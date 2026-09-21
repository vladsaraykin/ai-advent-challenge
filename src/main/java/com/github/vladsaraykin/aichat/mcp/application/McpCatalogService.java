package com.github.vladsaraykin.aichat.mcp.application;

import com.github.vladsaraykin.aichat.mcp.domain.McpServerView;
import com.github.vladsaraykin.aichat.mcp.domain.McpToolView;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class McpCatalogService {

    private static final Logger log = LoggerFactory.getLogger(McpCatalogService.class);
    private static final int MAX_TOOL_PAGES = 100;
    private final ObjectProvider<List<McpSyncClient>> clients;

    public McpCatalogService(ObjectProvider<List<McpSyncClient>> clients) {
        this.clients = clients;
    }

    public List<McpServerView> servers() {
        var configuredClients = clients.getIfAvailable(List::of);
        return configuredClients.stream().map(this::describe).toList();
    }

    private McpServerView describe(McpSyncClient client) {
        var connectionId = "mcp-server";
        try {
            connectionId = connectionId(client);
            var server = client.getServerInfo();
            var initialization = client.getCurrentInitializationResult();
            var tools = listAllTools(client).stream().map(McpCatalogService::toolView).toList();
            log.info("mcp_tools_listed connection={} server={} toolCount={}", connectionId, server.name(), tools.size());
            return new McpServerView(
                    connectionId,
                    firstNonBlank(server.title(), server.name(), connectionId),
                    server.version(),
                    initialization == null ? null : initialization.protocolVersion(),
                    client.isInitialized(),
                    null,
                    tools);
        }
        catch (RuntimeException exception) {
            log.warn("mcp_tools_list_failed connection={} errorType={}", connectionId,
                    exception.getClass().getSimpleName());
            return new McpServerView(connectionId, connectionId, null, null, false,
                    "Не удалось получить список инструментов от MCP-сервера.", List.of());
        }
    }

    private static List<McpSchema.Tool> listAllTools(McpSyncClient client) {
        var tools = new ArrayList<McpSchema.Tool>();
        String cursor = null;
        for (int page = 0; page < MAX_TOOL_PAGES; page++) {
            var result = cursor == null ? client.listTools() : client.listTools(cursor);
            tools.addAll(result.tools());
            var nextCursor = result.nextCursor();
            if (nextCursor == null || nextCursor.isBlank() || nextCursor.equals(cursor)) {
                return List.copyOf(tools);
            }
            cursor = nextCursor;
        }
        throw new IllegalStateException("MCP tools pagination exceeded the configured limit");
    }

    private static McpToolView toolView(McpSchema.Tool tool) {
        var annotations = tool.annotations();
        return new McpToolView(
                tool.name(),
                firstNonBlank(tool.title(), annotations == null ? null : annotations.title(), tool.name()),
                firstNonBlank(tool.description(), "Описание не предоставлено MCP-сервером"),
                tool.inputSchema() == null ? Map.of() : Map.copyOf(tool.inputSchema()),
                annotations == null ? null : annotations.readOnlyHint(),
                annotations == null ? null : annotations.destructiveHint());
    }

    private static String connectionId(McpSyncClient client) {
        var info = client.getClientInfo();
        var title = info == null ? null : info.title();
        if (title != null && !title.isBlank()) {
            return title.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        }
        return "mcp-server";
    }

    private static String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
