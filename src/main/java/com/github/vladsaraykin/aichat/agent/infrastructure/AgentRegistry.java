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
                    compression(properties));
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
        boolean enabled = Boolean.parseBoolean(properties.getProperty("context-compression.enabled", "false"));
        return new AgentDefinition.ContextCompression(enabled,
                Integer.parseInt(properties.getProperty("context-compression.recent-messages", "10")),
                Integer.parseInt(properties.getProperty("context-compression.summary-batch-size", "10")),
                Integer.parseInt(properties.getProperty("context-compression.summary-max-completion-tokens", "1000")),
                properties.getProperty("context-compression.system-prompt", enabled ? null : "disabled"));
    }
}
