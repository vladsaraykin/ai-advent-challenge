package com.github.vladsaraykin.aichat.agent;

import com.github.vladsaraykin.aichat.agent.infrastructure.AgentRegistry;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class AgentRegistryTest {
    @Test void loadsArchitectMemoryLayersAndValidatesConfiguration() throws Exception {
        var registry = new AgentRegistry((d, m) -> null, "classpath:agents/*.yaml");
        assertThat(registry.get("architect").definition().memoryLayers().enabled()).isTrue();
        assertThat(registry.get("architect").definition().model()).isEqualTo("gpt-5.6-sol");
        assertThat(registry.get("architect").definition().memoryLayers().maxCompletionTokens()).isEqualTo(8192);
        assertThat(registry.get("architect").definition().memoryLayers().questionsMaxTokens()).isEqualTo(4096);
        assertThat(registry.get("architect").definition().memoryLayers().questionsPrompt()).contains("questions");
        assertThat(registry.get("architect").definition().pricing()).isEqualTo(registry.get("techno").definition().pricing());
        assertThat(registry.get("chef").definition().memoryLayers().enabled()).isFalse();
        assertThatThrownBy(() -> new com.github.vladsaraykin.aichat.agent.domain.AgentDefinition.MemoryLayers(true, 3, 3000, "prompt"))
                .hasMessageContaining("memory layer");
        Files.writeString(directory.resolve("custom.yaml"), CONFIG + "memory-layers:\n  enabled: true\n");
        assertThatThrownBy(() -> new AgentRegistry((d, m) -> null, directory.toUri() + "*.yaml"))
                .hasMessageContaining("memory layer");
    }
    @Test void questionSyncSettingsHaveDefaultsAndRejectInvalidLimits() throws Exception {
        String settings = "memory-layers:\n  enabled: true\n  system-prompt: Extract task JSON.\n";
        Files.writeString(directory.resolve("custom.yaml"), CONFIG + settings);
        var defaults = new AgentRegistry((d, m) -> null, directory.toUri() + "*.yaml")
                .get("custom").definition().memoryLayers();
        assertThat(defaults.questionsMaxTokens()).isEqualTo(4096);
        assertThat(defaults.questionsPrompt()).contains("questions");
        Files.writeString(directory.resolve("custom.yaml"), CONFIG + settings
                + "  questions-max-tokens: 1024\n  questions-prompt: Extract questions JSON.\n");
        var custom = new AgentRegistry((d, m) -> null, directory.toUri() + "*.yaml")
                .get("custom").definition().memoryLayers();
        assertThat(custom.questionsMaxTokens()).isEqualTo(1024);
        assertThat(custom.questionsPrompt()).isEqualTo("Extract questions JSON.");
        Files.writeString(directory.resolve("custom.yaml"), CONFIG + settings + "  questions-max-tokens: 0\n");
        assertThatThrownBy(() -> new AgentRegistry((d, m) -> null, directory.toUri() + "*.yaml"))
                .hasMessageContaining("memory layer");
    }
    @TempDir Path directory;
    private static final String CONFIG = """
            id: custom
            name: Custom agent
            description: Custom role
            model: gpt-4.1-mini
            max-completion-tokens: 8192
            pricing:
              input-per-million-usd: 0.40
              cached-input-per-million-usd: 0.10
              output-per-million-usd: 1.60
            system-prompt: |
              Ты новый агент.
              Используй собственные инструкции.
            """;

    @Test void externalYamlCreatesAgentWithoutCodeChanges() throws Exception {
        Files.writeString(directory.resolve("custom.yaml"), CONFIG);
        var registry = new AgentRegistry((definition, messages) -> null, directory.toUri() + "*.yaml");
        assertThat(registry.definitions()).hasSize(1);
        assertThat(registry.get("custom").definition().maxCompletionTokens()).isEqualTo(8192);
        assertThat(registry.get("custom").definition().pricing().outputPerMillionUsd()).isEqualByComparingTo("1.60");
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

    @Test void missingOrInvalidPricingFailsAtStartup() throws Exception {
        Files.writeString(directory.resolve("custom.yaml"), CONFIG.replace("  output-per-million-usd: 1.60\n", ""));
        assertThatThrownBy(() -> new AgentRegistry((definition, messages) -> null, directory.toUri() + "*.yaml"))
                .hasMessageContaining("pricing.output-per-million-usd");
        Files.writeString(directory.resolve("custom.yaml"), CONFIG.replace("cached-input-per-million-usd: 0.10",
                "cached-input-per-million-usd: 0.50"));
        assertThatThrownBy(() -> new AgentRegistry((definition, messages) -> null, directory.toUri() + "*.yaml"))
                .hasMessageContaining("Cached input price");
    }

    @Test void validatesAndLoadsContextCompressionSettings() throws Exception {
        String compression = """
                context-compression:
                  enabled: true
                  recent-messages: 8
                  summary-batch-size: 6
                  summary-max-completion-tokens: 512
                  system-prompt: Сохрани важные факты.
                """;
        Files.writeString(directory.resolve("custom.yaml"), CONFIG.replace("pricing:\n", compression + "pricing:\n"));
        var registry = new AgentRegistry((definition, messages) -> null, directory.toUri() + "*.yaml");
        var settings = registry.get("custom").definition().compression();
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.recentMessages()).isEqualTo(8);
        assertThat(settings.batchSize()).isEqualTo(6);
        assertThat(settings.maxCompletionTokens()).isEqualTo(512);

        Files.writeString(directory.resolve("custom.yaml"), CONFIG.replace("pricing:\n",
                compression.replace("recent-messages: 8", "recent-messages: 7") + "pricing:\n"));
        assertThatThrownBy(() -> new AgentRegistry((definition, messages) -> null, directory.toUri() + "*.yaml"))
                .hasMessageContaining("compression");
    }

    @Test void loadsStrategyDefaultsAndRejectsInvalidFactsWindow() throws Exception {
        String settings = """
                context-management:
                  default-strategy: FACTS
                  sliding-window:
                    recent-messages: 6
                  facts:
                    recent-messages: 8
                    max-completion-tokens: 512
                    system-prompt: Extract JSON facts.
                """;
        Files.writeString(directory.resolve("custom.yaml"), CONFIG + settings);
        var registry = new AgentRegistry((definition, messages) -> null, directory.toUri() + "*.yaml");
        var config = registry.get("custom").definition().contextManagement();
        assertThat(config.defaultStrategy().name()).isEqualTo("FACTS");
        assertThat(config.slidingMessages()).isEqualTo(6);
        assertThat(config.factsMessages()).isEqualTo(8);
        assertThat(config.factsMaxTokens()).isEqualTo(512);
        Files.writeString(directory.resolve("custom.yaml"), CONFIG + settings.replace("recent-messages: 8", "recent-messages: 7"));
        assertThatThrownBy(() -> new AgentRegistry((definition, messages) -> null, directory.toUri() + "*.yaml"))
                .hasMessageContaining("context management");
    }
}
