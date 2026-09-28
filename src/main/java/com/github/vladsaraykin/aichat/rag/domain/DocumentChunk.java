package com.github.vladsaraykin.aichat.rag.domain;

import java.util.UUID;

public record DocumentChunk(UUID chunkId, int ordinal, String source, String title, String section,
                            Integer pageStart, Integer pageEnd, int startOffset, int endOffset,
                            String content, int estimatedTokens) {
    public enum Strategy { FIXED_SIZE, STRUCTURAL }
}
