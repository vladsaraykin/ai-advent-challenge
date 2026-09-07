package com.github.vladsaraykin.aichat.agent.application;

public class ChatFailure extends RuntimeException {
    public enum Kind { NOT_FOUND, BUSY, INVALID, PROVIDER, STORAGE }
    private final Kind kind;
    public ChatFailure(Kind kind, String message) { super(message); this.kind = kind; }
    public Kind kind() { return kind; }
}
