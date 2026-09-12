package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.*;
import reactor.core.publisher.Mono;

public final class FactsContextStrategy implements ContextStrategy {
    @Override public String beforePhase(Agent agent, Chat chat) { return "UPDATING_FACTS"; }
    @Override public Mono<Chat> before(Agent agent, Chat chat, ChatMessage user) {
        Chat providerContext = context(agent, chat);
        return agent.updateFacts(providerContext, user).map(chat::withMemory);
    }
    @Override public Chat context(Agent agent, Chat chat) {
        return chat.contextWindow(agent.definition().contextManagement().factsMessages());
    }
}
