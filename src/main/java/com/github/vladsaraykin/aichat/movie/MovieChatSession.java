package com.github.vladsaraykin.aichat.movie;

import com.github.vladsaraykin.aichat.history.ChatMessage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class MovieChatSession {

    private final Map<MovieChatMode, List<ChatMessage>> conversations = new EnumMap<>(MovieChatMode.class);

    public MovieChatSession() {
        conversations.put(MovieChatMode.DEFAULT, new ArrayList<>());
        conversations.put(MovieChatMode.CONTROLLED, new ArrayList<>());
    }

    public List<ChatMessage> messages(MovieChatMode mode) {
        return List.copyOf(conversations.get(mode));
    }

    public void add(MovieChatMode mode, ChatMessage message) {
        conversations.get(mode).add(message);
    }

    public void clear(MovieChatMode mode) {
        conversations.get(mode).clear();
    }
}
