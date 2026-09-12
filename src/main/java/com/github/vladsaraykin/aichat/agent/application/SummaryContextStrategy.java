package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.*;
import reactor.core.publisher.Mono;

public final class SummaryContextStrategy implements ContextStrategy {
    @Override public String beforePhase(Agent agent, Chat chat) { return afterPhase(agent, chat); }
    @Override public String afterPhase(Agent agent, Chat chat) {
        var config = agent.definition().compression();
        return config.enabled() && chat.messages().size() - config.recentMessages() >= config.batchSize()
                ? "SUMMARIZING" : null;
    }
    @Override public Mono<Chat> before(Agent agent, Chat chat, ChatMessage user) { return after(agent, chat); }
    @Override public Mono<Chat> after(Agent agent, Chat chat) {
        if (afterPhase(agent, chat) == null) return Mono.just(chat);
        int removed = chat.messages().size() - agent.definition().compression().recentMessages();
        return agent.summarize(chat.summary(), chat.messages().subList(0, removed))
                .map(summary -> chat.compact(summary, removed));
    }
}
