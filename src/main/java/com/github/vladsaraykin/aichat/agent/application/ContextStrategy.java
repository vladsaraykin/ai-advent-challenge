package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.*;
import reactor.core.publisher.Mono;

/** Each strategy owns memory preparation and retention, never transport or storage. */
public interface ContextStrategy {
    /** Provider-only view; persistence always uses the prepared full chat. */
    default Chat context(Agent agent, Chat chat) { return chat; }
    default String beforePhase(Agent agent, Chat chat) { return null; }
    default Mono<Chat> before(Agent agent, Chat chat, ChatMessage user) { return Mono.just(chat); }
    default String afterPhase(Agent agent, Chat chat) { return null; }
    default Mono<Chat> after(Agent agent, Chat chat) { return Mono.just(chat); }

    static ContextStrategy forType(ContextStrategyType type) {
        return switch (type) {
            case SUMMARY -> new SummaryContextStrategy();
            case SLIDING_WINDOW -> new SlidingWindowContextStrategy();
            case FACTS -> new FactsContextStrategy();
            case BRANCHING -> new BranchingContextStrategy();
        };
    }
}
