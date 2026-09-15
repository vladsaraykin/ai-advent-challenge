package com.github.vladsaraykin.aichat.agent;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.agent.infrastructure.AgentRegistry;
import java.util.*;

/** Stable no-layers fixture: Day 10 strategy invariants are tested independently of Day 11 extraction. */
final class LegacyAgents {
    static AgentCatalog catalog(ConversationModel model) throws Exception {
        var registry = new AgentRegistry(model, "classpath:agents/*.yaml");
        var definitions = registry.definitions().stream().map(d -> new AgentDefinition(d.id(), d.name(), d.description(),
                d.model(), d.systemPrompt(), d.maxCompletionTokens(), d.reasoningEffort(), d.timeoutSeconds(), d.maxHistoryChars(),
                d.pricing(), d.compression(), new AgentDefinition.ContextManagement(ContextStrategyType.SUMMARY,
                d.contextManagement().slidingMessages(), d.contextManagement().factsMessages(), d.contextManagement().factsMaxTokens(),
                d.contextManagement().factsPrompt()))).toList();
        return new AgentCatalog() {
            public List<AgentDefinition> definitions() { return definitions; }
            public Agent get(String id) { return new ConfiguredAgent(definitions.stream().filter(d -> d.id().equals(id)).findFirst()
                    .orElseThrow(() -> new ChatFailure(ChatFailure.Kind.NOT_FOUND, "Агент не найден")), model); }
        };
    }
}
