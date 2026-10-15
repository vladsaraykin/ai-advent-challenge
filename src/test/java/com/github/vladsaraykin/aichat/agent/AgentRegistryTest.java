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
        assertThat(registry.get("architect").definition().memoryLayers().systemPrompt())
                .contains("Временные команды процесса", "implementation_scope");
        assertThat(registry.get("architect").definition().invariants().enabled()).isTrue();
        assertThat(registry.get("architect").definition().invariants().guardPrompt()).contains("CONFLICT");
        assertThat(registry.get("architect").definition().lifecycle().enabled()).isTrue();
        assertThat(registry.get("architect").definition().lifecycle().guardPrompt()).contains("PREMATURE_EXECUTION");
        assertThat(registry.get("architect").definition().pricing()).isEqualTo(registry.get("techno").definition().pricing());
        assertThat(registry.get("assistant").definition().name()).isEqualTo("Личный ассистент");
        assertThat(registry.get("assistant").definition().model()).isEqualTo("gpt-5.6-sol");
        assertThat(registry.get("assistant").definition().contextManagement().defaultStrategy().name())
                .isEqualTo("SLIDING_WINDOW");
        assertThat(registry.get("assistant").definition().memoryLayers().enabled()).isTrue();
        assertThat(registry.get("assistant").definition().systemPrompt()).contains("MCP-инструменты");
        assertThat(registry.get("chef").definition().memoryLayers().enabled()).isTrue();
        assertThat(registry.get("chef").definition().invariants().enabled()).isTrue();
        assertThat(registry.get("chef").definition().lifecycle().enabled()).isTrue();
        var chef = registry.get("chef").definition();
        assertThat(chef.reasoningEffort()).isEqualTo("medium");
        assertThat(chef.pricing().inputPerMillionUsd()).isEqualByComparingTo("4.00");
        assertThat(chef.modelOptions()).extracting(com.github.vladsaraykin.aichat.agent.domain.AgentDefinition.ModelOption::id)
                .contains("gpt-4.1-mini", "gpt-5.6-sol");
        var selected = chef.withModel("gpt-5.6-sol", "xhigh");
        assertThat(selected.reasoningEffort()).isEqualTo("xhigh");
        assertThat(selected.pricing().inputPerMillionUsd()).isEqualByComparingTo("4.00");
        assertThat(selected.withPrompt("temporary prompt", 512).reasoningEffort()).isEqualTo("xhigh");
        assertThatThrownBy(() -> chef.withModel("gpt-4.1-mini", "high"))
                .hasMessageContaining("not available");
        var context = new com.github.vladsaraykin.aichat.harness.domain.RequestContext("owner", "chef",
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "question",
                new com.github.vladsaraykin.aichat.harness.domain.ChatSettings(0, false, java.util.List.of(), null,
                        com.github.vladsaraykin.aichat.harness.domain.ChatSettings.LlmProvider.OPENAI,
                        "gpt-5.6-sol", "xhigh"), null, null, null);
        var requestAgent = registry.get("chef").withRequestContext(context);
        assertThat(requestAgent.definition().model()).isEqualTo("gpt-5.6-sol");
        assertThat(requestAgent.definition().reasoningEffort()).isEqualTo("xhigh");
        assertThat(requestAgent.definition().pricing().outputPerMillionUsd()).isEqualByComparingTo("20.00");
        assertThatThrownBy(() -> new com.github.vladsaraykin.aichat.agent.domain.AgentDefinition.MemoryLayers(true, 3, 3000, "prompt"))
                .hasMessageContaining("memory layer");
        Files.writeString(directory.resolve("custom.yaml"), CONFIG + "memory-layers:\n  enabled: true\n  recent-messages: 3\n");
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
        assertThat(registry.get("custom").definition().model()).isEqualTo("gpt-5.6-sol");
        assertThat(registry.get("custom").definition().pricing().outputPerMillionUsd()).isEqualByComparingTo("20.00");
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

    @Test void invalidSharedDefaultModelFailsAtStartup() throws Exception {
        Files.writeString(directory.resolve("custom.yaml"), CONFIG + "default-model: unknown-model\n");
        assertThatThrownBy(() -> new AgentRegistry((definition, messages) -> null, directory.toUri() + "*.yaml"))
                .hasMessageContaining("Default model is not listed in model-options");
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
        Files.writeString(directory.resolve("custom.yaml"), CONFIG + compression);
        var registry = new AgentRegistry((definition, messages) -> null, directory.toUri() + "*.yaml");
        var settings = registry.get("custom").definition().compression();
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.recentMessages()).isEqualTo(8);
        assertThat(settings.batchSize()).isEqualTo(6);
        assertThat(settings.maxCompletionTokens()).isEqualTo(512);

        Files.writeString(directory.resolve("custom.yaml"), CONFIG + compression.replace("recent-messages: 8", "recent-messages: 7"));
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
