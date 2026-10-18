package com.github.vladsaraykin.aichat.mcp.application;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class McpCallBudgetTest {
    @Test void defaultsPermitLongTasksButRemainFinite() {
        assertThat(McpCallBudget.defaults().maxDistinctCalls()).isEqualTo(256);
        assertThat(McpCallBudget.defaults().maxCallbackAttempts()).isEqualTo(1024);
    }

    @Test void springReadsServerOwnedConfiguration() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("app.mcp.max-distinct-calls", "64", "app.mcp.max-callback-attempts", "128")));
            context.register(McpCallBudget.class);
            context.refresh();
            assertThat(context.getBean(McpCallBudget.class)).isEqualTo(new McpCallBudget(64, 128));
        }
    }

    @Test void rejectsUnboundedOrInconsistentBudgets() {
        for (int[] values : new int[][]{{0, 1024}, {4097, 8192}, {256, 24}, {256, 16385}})
            assertThatThrownBy(() -> new McpCallBudget(values[0], values[1]))
                    .isInstanceOf(IllegalArgumentException.class);
    }
}
