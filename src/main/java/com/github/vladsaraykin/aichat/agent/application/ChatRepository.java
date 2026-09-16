package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.Chat;
import java.util.List;
import java.util.UUID;

public interface ChatRepository {
    String LEGACY_OWNER = "local";
    List<Chat> list(String ownerId, String agentId);
    Chat get(String ownerId, String agentId, UUID chatId);
    void save(String ownerId, Chat chat);
    void delete(String ownerId, String agentId, UUID chatId);
    default List<Chat> list(String agentId) { return list(LEGACY_OWNER, agentId); }
    default Chat get(String agentId, UUID chatId) { return get(LEGACY_OWNER, agentId, chatId); }
    default void save(Chat chat) { save(LEGACY_OWNER, chat); }
    default void delete(String agentId, UUID chatId) { delete(LEGACY_OWNER, agentId, chatId); }
}
