package com.github.vladsaraykin.aichat.mcp.domain;

import java.util.Map;

public record McpToolView(
        String name,
        String title,
        String description,
        Map<String, Object> inputSchema,
        Boolean readOnly,
        Boolean destructive) {
}
