package com.github.vladsaraykin.aichat.agent;

import com.github.vladsaraykin.aichat.agent.infrastructure.AgentRegistry;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class AgentRegistryTest {
    @TempDir Path directory;
    private static final String CONFIG = """
            id: custom
            name: Custom agent
            description: Custom role
            model: gpt-4.1-mini
            max-completion-tokens: 8192
            system-prompt: |
              Ты новый агент.
              Используй собственные инструкции.
            """;

    @Test void externalYamlCreatesAgentWithoutCodeChanges() throws Exception {
        Files.writeString(directory.resolve("custom.yaml"), CONFIG);
        var registry = new AgentRegistry((definition, messages) -> null, directory.toUri() + "*.yaml");
        assertThat(registry.definitions()).hasSize(1);
        assertThat(registry.get("custom").definition().maxCompletionTokens()).isEqualTo(8192);
        assertThat(registry.get("custom").definition().systemPrompt()).contains("Используй собственные инструкции");
    }

    @Test void invalidOrDuplicateDefinitionsFailAtStartup() throws Exception {
        Files.writeString(directory.resolve("one.yaml"), CONFIG);
        Files.writeString(directory.resolve("two.yaml"), CONFIG);
        assertThatThrownBy(() -> new AgentRegistry((definition, messages) -> null, directory.toUri() + "*.yaml"))
                .hasMessageContaining("Duplicate agent id");
        Files.writeString(directory.resolve("two.yaml"), CONFIG.replace("id: custom", "id: ../outside"));
        assertThatThrownBy(() -> new AgentRegistry((definition, messages) -> null, directory.toUri() + "*.yaml"))
                .hasMessageContaining("Invalid agent id");
    }
}
