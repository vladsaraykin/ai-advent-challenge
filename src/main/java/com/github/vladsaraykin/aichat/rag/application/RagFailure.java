package com.github.vladsaraykin.aichat.rag.application;

public final class RagFailure extends RuntimeException {
    public enum Kind { INVALID, TOO_LARGE, UNSUPPORTED, NO_TEXT, BUSY, NOT_FOUND, STORAGE }
    private final Kind kind;
    public RagFailure(Kind kind, String message) { super(message); this.kind = kind; }
    public Kind kind() { return kind; }
    public static RagFailure storage() {
        return new RagFailure(Kind.STORAGE, "Не удалось сохранить или прочитать документ. Повторите попытку позже.");
    }
}
