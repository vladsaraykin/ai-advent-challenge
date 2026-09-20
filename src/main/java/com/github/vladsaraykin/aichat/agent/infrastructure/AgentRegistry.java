package com.github.vladsaraykin.aichat.agent.infrastructure;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.AgentDefinition;
import com.github.vladsaraykin.aichat.agent.domain.TokenPricing;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
public class AgentRegistry implements AgentCatalog {
    private final Map<String, Agent> agents;

    public AgentRegistry(ConversationModel model,
                         @Value("${app.agents.location:classpath:agents/*.yaml}") String location) throws IOException {
        var resources = new PathMatchingResourcePatternResolver().getResources(location);
        Arrays.sort(resources, Comparator.comparing(resource -> Objects.toString(resource.getFilename(), "")));
        if (resources.length == 0) throw new IllegalArgumentException("No agent YAML files found");
        Map<String, Agent> loaded = new LinkedHashMap<>();
        for (var resource : resources) {
            var yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(resource);
            Properties properties = Objects.requireNonNull(yaml.getObject());
            var definition = new AgentDefinition(properties.getProperty("id"), properties.getProperty("name"),
                    properties.getProperty("description"), properties.getProperty("model"),
                    properties.getProperty("system-prompt"),
                    Integer.parseInt(properties.getProperty("max-completion-tokens", "4096")),
                    properties.getProperty("reasoning-effort"),
                    Integer.parseInt(properties.getProperty("timeout-seconds", "120")),
                    Integer.parseInt(properties.getProperty("max-history-chars", "60000")),
                    new TokenPricing(requiredDecimal(properties, "pricing.input-per-million-usd"),
                            requiredDecimal(properties, "pricing.cached-input-per-million-usd"),
                            requiredDecimal(properties, "pricing.output-per-million-usd")),
                    compression(properties), management(properties), layers(properties), invariants(properties), lifecycle(properties));
            if (loaded.putIfAbsent(definition.id(), new ConfiguredAgent(definition, model)) != null) {
                throw new IllegalArgumentException("Duplicate agent id: " + definition.id());
            }
        }
        agents = Collections.unmodifiableMap(loaded);
    }

    public List<AgentDefinition> definitions() {
        return agents.values().stream().map(Agent::definition).toList();
    }
    public Agent get(String id) {
        Agent agent = agents.get(id);
        if (agent == null) throw new ChatFailure(ChatFailure.Kind.NOT_FOUND, "Агент не найден");
        return agent;
    }

    private static BigDecimal requiredDecimal(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing agent setting: " + key);
        try { return new BigDecimal(value); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("Invalid agent setting: " + key); }
    }

    private static AgentDefinition.ContextCompression compression(Properties properties) {
        boolean modern = properties.containsKey("context-management.summary.enabled");
        String prefix = modern ? "context-management.summary." : "context-compression.";
        boolean enabled = Boolean.parseBoolean(properties.getProperty(prefix + "enabled", "false"));
        return new AgentDefinition.ContextCompression(enabled,
                Integer.parseInt(properties.getProperty(prefix + "recent-messages", "10")),
                Integer.parseInt(properties.getProperty(prefix + (modern ? "batch-size" : "summary-batch-size"), "10")),
                Integer.parseInt(properties.getProperty(prefix + (modern ? "max-completion-tokens" : "summary-max-completion-tokens"), "1000")),
                properties.getProperty(prefix + "system-prompt", enabled ? null : "disabled"));
    }
    private static AgentDefinition.ContextManagement management(Properties p) {
        var defaults = AgentDefinition.ContextManagement.defaults();
        return new AgentDefinition.ContextManagement(
                com.github.vladsaraykin.aichat.agent.domain.ContextStrategyType.valueOf(
                        p.getProperty("context-management.default-strategy", "SUMMARY").toUpperCase(Locale.ROOT)),
                Integer.parseInt(p.getProperty("context-management.sliding-window.recent-messages", "10")),
                Integer.parseInt(p.getProperty("context-management.facts.recent-messages", "10")),
                Integer.parseInt(p.getProperty("context-management.facts.max-completion-tokens", "1000")),
                p.getProperty("context-management.facts.system-prompt", defaults.factsPrompt()));
    }
    private static AgentDefinition.MemoryLayers layers(Properties p) {
        boolean enabled = Boolean.parseBoolean(p.getProperty("memory-layers.enabled", "false"));
        return new AgentDefinition.MemoryLayers(enabled,
                Integer.parseInt(p.getProperty("memory-layers.recent-messages", "10")),
                Integer.parseInt(p.getProperty("memory-layers.max-completion-tokens", "3000")),
                p.getProperty("memory-layers.system-prompt", enabled ? null : "disabled"),
                Integer.parseInt(p.getProperty("memory-layers.questions-max-tokens", "4096")),
                p.getProperty("memory-layers.questions-prompt", AgentDefinition.MemoryLayers.defaultQuestionsPrompt()));
    }
    private static AgentDefinition.InvariantSettings invariants(Properties p) {
        boolean enabled = Boolean.parseBoolean(p.getProperty("invariants.enabled", "false"));
        return new AgentDefinition.InvariantSettings(enabled,
                Integer.parseInt(p.getProperty("invariants.max-completion-tokens", "600")),
                p.getProperty("invariants.guard-prompt", enabled ? null : "disabled"));
    }
    private static AgentDefinition.LifecycleSettings lifecycle(Properties p) {
        boolean enabled = Boolean.parseBoolean(p.getProperty("lifecycle.enabled", "false"));
        return new AgentDefinition.LifecycleSettings(enabled,
                Integer.parseInt(p.getProperty("lifecycle.max-completion-tokens", "600")),
                p.getProperty("lifecycle.guard-prompt", enabled ? null : "disabled"));
    }
}
