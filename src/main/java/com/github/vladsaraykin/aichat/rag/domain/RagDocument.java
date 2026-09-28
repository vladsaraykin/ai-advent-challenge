package com.github.vladsaraykin.aichat.rag.domain;

import java.time.Instant;
import java.util.UUID;

/** Internal persistence value; storage keys are not part of the HTTP response. */
public record RagDocument(UUID id, String owner, String filename, String mediaType,
                          UUID storageKey, long byteSize, String sha256,
                          ExtractedText extracted, Instant createdAt) {
    public record Summary(UUID id, String filename, String mediaType, long byteSize,
                          int characterCount, Instant createdAt) { }
    public Summary summary() {
        return new Summary(id, filename, mediaType, byteSize,
                extracted.text().codePointCount(0, extracted.text().length()), createdAt);
    }
}
