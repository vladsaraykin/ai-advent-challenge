package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.AgentDefinition;
import com.github.vladsaraykin.aichat.agent.domain.ChatMessage;
import java.util.List;

public interface Agent {
    AgentDefinition definition();
    ChatMessage answer(List<ChatMessage> history, ChatMessage userMessage);
}
