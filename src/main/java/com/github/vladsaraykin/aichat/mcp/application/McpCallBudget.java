package com.github.vladsaraykin.aichat.mcp.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Distinct calls are persisted; callback attempts also include cached results and refusals. */
@Component
public record McpCallBudget(int maxDistinctCalls, int maxCallbackAttempts) {
    public static final int DEFAULT_DISTINCT = 256;
    public static final int DEFAULT_ATTEMPTS = 1024;

    public McpCallBudget(
            @Value("${app.mcp.max-distinct-calls:256}") int maxDistinctCalls,
            @Value("${app.mcp.max-callback-attempts:1024}") int maxCallbackAttempts) {
        if (maxDistinctCalls < 1 || maxDistinctCalls > 4096
                || maxCallbackAttempts < maxDistinctCalls || maxCallbackAttempts > 16384) {
            throw new IllegalArgumentException("MCP budgets require 1 <= distinct <= 4096 and distinct <= attempts <= 16384");
        }
        this.maxDistinctCalls = maxDistinctCalls;
        this.maxCallbackAttempts = maxCallbackAttempts;
    }

    public static McpCallBudget defaults() {
        return new McpCallBudget(DEFAULT_DISTINCT, DEFAULT_ATTEMPTS);
    }
}
