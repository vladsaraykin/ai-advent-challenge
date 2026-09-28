package com.github.vladsaraykin.aichat.rag.application;

/** Size estimate only; never provider usage or an embeddinggemma context guarantee. */
@FunctionalInterface
public interface ChunkTokenEstimator {
    int count(String text);
}
