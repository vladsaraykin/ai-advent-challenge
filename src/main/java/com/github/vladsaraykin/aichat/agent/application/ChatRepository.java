package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.Chat;
import java.util.List;
import java.util.UUID;

public interface ChatRepository {
    List<Chat> list(String agentId);
    Chat get(String agentId, UUID chatId);
    void save(Chat chat);
    void delete(String agentId, UUID chatId);
}
