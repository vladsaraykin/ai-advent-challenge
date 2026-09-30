package com.github.vladsaraykin.aichat.rag.application;

import java.util.List;

public interface Reranker {
    /** Scores in exactly the same order as documents, sigmoid-normalized to [0,1]. */
    List<Double> score(String question, List<String> documents);
    final class Failure extends RuntimeException {
        public Failure() { super("Reranker недоступен или вернул некорректные оценки. Проверьте сервис reranker."); }
    }
}
