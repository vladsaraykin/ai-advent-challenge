package com.github.vladsaraykin.aichat.rag.application;

public interface EmbeddingModel {
    record Result(float[] vector, Long promptTokens) { }
    Result embed(String text);

    final class Failure extends RuntimeException {
        private final String code;
        public Failure(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
