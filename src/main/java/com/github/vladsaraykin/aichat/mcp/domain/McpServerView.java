package com.github.vladsaraykin.aichat.mcp.domain;

import java.util.List;

public record McpServerView(
        String id,
        String name,
        String version,
        String protocolVersion,
        boolean connected,
        String error,
        List<McpToolView> tools) {
}
