package com.github.vladsaraykin.aichat.mcp.application;

import com.github.vladsaraykin.aichat.agent.application.ChatFailure;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Resolves tools from one explicitly selected MCP connection for a primary agent answer. */
@Service
public class McpToolService {

    private static final Logger log = LoggerFactory.getLogger(McpToolService.class);
    private final ObjectProvider<List<McpSyncClient>> clients;

    public McpToolService(ObjectProvider<List<McpSyncClient>> clients) {
        this.clients = clients;
    }

    public List<ToolCallback> callbacks(String serverId) {
        if (serverId == null || serverId.isBlank()) return List.of();
        var client = clients.getIfAvailable(List::of).stream()
                .filter(value -> McpCatalogService.connectionId(value).equals(serverId))
                .findFirst()
                .orElseThrow(() -> new ChatFailure(ChatFailure.Kind.INVALID,
                        "Выбранный MCP-сервер недоступен. Обновите список подключений."));
        if (!client.isInitialized()) {
            throw new ChatFailure(ChatFailure.Kind.INVALID,
                    "Выбранный MCP-сервер ещё не установил соединение.");
        }
        var callbacks = List.of(new SyncMcpToolCallbackProvider(client).getToolCallbacks()).stream()
                .map(callback -> logging(serverId, callback))
                .toList();
        if (callbacks.isEmpty()) {
            throw new ChatFailure(ChatFailure.Kind.INVALID,
                    "Выбранный MCP-сервер не предоставил инструментов.");
        }
        return callbacks;
    }

    private static ToolCallback logging(String serverId, ToolCallback delegate) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
            @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
            @Override public String call(String input) { return invoke(() -> delegate.call(input)); }
            @Override public String call(String input, org.springframework.ai.chat.model.ToolContext context) {
                return invoke(() -> delegate.call(input, context));
            }
            private String invoke(java.util.function.Supplier<String> action) {
                long started = System.nanoTime();
                String tool = delegate.getToolDefinition().name();
                log.info("mcp_tool_started connection={} tool={}", serverId, tool);
                try {
                    String result = action.get();
                    log.info("mcp_tool_completed connection={} tool={} durationMs={} resultChars={}", serverId,
                            tool, (System.nanoTime() - started) / 1_000_000, result == null ? 0 : result.length());
                    return result;
                } catch (RuntimeException exception) {
                    log.warn("mcp_tool_failed connection={} tool={} durationMs={} errorType={}", serverId, tool,
                            (System.nanoTime() - started) / 1_000_000, exception.getClass().getSimpleName());
                    throw exception;
                }
            }
        };
    }
}
