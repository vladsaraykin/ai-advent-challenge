package com.github.vladsaraykin.aichat.rag.application;

import com.github.vladsaraykin.aichat.rag.domain.DocumentChunk;
import com.github.vladsaraykin.aichat.rag.domain.IndexRun;
import java.util.UUID;

/** Narrow use-case port for indexing managed repository documents. */
public interface ProjectDocumentIndexer {
    IndexRun start(String owner, UUID documentId, DocumentChunk.Strategy strategy);
    IndexRun get(String owner, UUID runId);
}
