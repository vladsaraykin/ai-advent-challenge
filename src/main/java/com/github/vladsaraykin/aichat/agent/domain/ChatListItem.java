package com.github.vladsaraykin.aichat.agent.domain;

import java.time.Instant;
import java.util.UUID;

/** Sidebar metadata only: no transcripts, task memory, or provider configuration. */
public record ChatListItem(UUID id, String agentId, String title, Instant updatedAt, int messageCount,
                           ContextStrategyType strategy, UUID parentChatId, UUID checkpointId, boolean readOnly) {
    public static ChatListItem from(Chat chat) {
        return new ChatListItem(chat.id(), chat.agentId(), chat.title(), chat.updatedAt(), chat.messageCount(),
                chat.strategy(), chat.parentChatId(), chat.checkpointId(), chat.readOnly());
    }
}
