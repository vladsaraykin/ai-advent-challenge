package com.github.vladsaraykin.aichat.rag.application;

import com.github.vladsaraykin.aichat.rag.domain.*;
import java.util.*;

public interface IndexRepository {
    void recoverInterrupted();
    UUID create(RagDocument document, DocumentChunk.Strategy strategy, ChunkingSettings settings, String model);
    void prepare(String owner, UUID run, List<DocumentChunk> chunks);
    void saveEmbedding(String owner, UUID run, int ordinal, EmbeddingModel.Result result);
    void finish(String owner, UUID run, long durationMs, String errorCode);
    IndexRun get(String owner, UUID run);
    List<IndexRun> list(String owner, UUID document);
    List<DocumentChunk> chunks(String owner, UUID run, int offset, int limit);
}
