package com.github.vladsaraykin.aichat.history;

public interface ChatHistoryStore {

    ChatConversation load();

    void save(ChatConversation conversation);

    void clear();
}
