package com.github.vladsaraykin.aichat.chat;

import com.github.vladsaraykin.aichat.history.ChatMessage;
import java.util.List;

public interface LlmClient {

    AiProvider provider();

    String generate(ChatForm form, List<ChatMessage> history);
}
