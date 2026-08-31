package com.github.vladsaraykin.aichat.chat;

public enum AiProvider {
    OPENAI("OpenAI");

    private final String displayName;

    AiProvider(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
