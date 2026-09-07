package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.AgentDefinition;
import com.github.vladsaraykin.aichat.agent.domain.ChatMessage;
import java.util.List;

public interface ConversationModel {
    Reply reply(AgentDefinition definition, List<ChatMessage> messages);
    record Reply(String text, ChatMessage.Metrics metrics) { }
}
