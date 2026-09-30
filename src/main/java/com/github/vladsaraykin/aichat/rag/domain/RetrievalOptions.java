package com.github.vladsaraykin.aichat.rag.domain;

/** User-adjustable retrieval parameters; never provider credentials or pricing. */
public record RetrievalOptions(int candidateK, int finalK, double threshold) {
    public RetrievalOptions {
        if (candidateK < 1 || candidateK > 50 || finalK < 1 || finalK > 20 || finalK > candidateK
                || !Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("Top-K до: 1–50, после: 1–20 и не больше первого; порог: 0–1.");
        }
    }
}
