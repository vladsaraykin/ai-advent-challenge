package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.*;

public final class SlidingWindowContextStrategy implements ContextStrategy {
    @Override public Chat context(Agent agent, Chat chat) {
        return chat.contextWindow(agent.definition().contextManagement().slidingMessages());
    }
}
