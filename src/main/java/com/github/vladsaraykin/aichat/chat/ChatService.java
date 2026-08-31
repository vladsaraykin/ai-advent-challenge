package com.github.vladsaraykin.aichat.chat;

import com.github.vladsaraykin.aichat.history.ChatConversation;
import com.github.vladsaraykin.aichat.history.ChatHistoryStore;
import com.github.vladsaraykin.aichat.history.ChatMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private final ChatHistoryStore historyStore;
    private final Map<AiProvider, LlmClient> clients;
    private final ReentrantLock conversationLock = new ReentrantLock();

    public ChatService(ChatHistoryStore historyStore, List<LlmClient> clients) {
        this.historyStore = historyStore;
        this.clients = clients.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                LlmClient::provider, client -> client));
    }

    public ChatConversation conversation() {
        return historyStore.load();
    }

    public void send(ChatForm form) {
        conversationLock.lock();
        try {
            ChatConversation conversation = historyStore.load();
            LlmClient client = clients.get(form.getProvider());
            if (client == null) {
                throw new IllegalArgumentException("Unsupported AI provider: " + form.getProvider());
            }
            String answer = client.generate(form, conversation.messages());

            Instant now = Instant.now();
            List<ChatMessage> updatedMessages = new ArrayList<>(conversation.messages());
            updatedMessages.add(new ChatMessage(ChatMessage.Role.USER, form.getUserPrompt(), now));
            updatedMessages.add(new ChatMessage(ChatMessage.Role.ASSISTANT, answer, Instant.now()));
            historyStore.save(new ChatConversation(form.getSystemPrompt(), updatedMessages, Instant.now()));
        } finally {
            conversationLock.unlock();
        }
    }

    public void clear() {
        conversationLock.lock();
        try {
            historyStore.clear();
        } finally {
            conversationLock.unlock();
        }
    }

}
