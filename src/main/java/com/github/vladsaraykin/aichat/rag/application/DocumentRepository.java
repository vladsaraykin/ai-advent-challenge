package com.github.vladsaraykin.aichat.rag.application;

import com.github.vladsaraykin.aichat.rag.domain.RagDocument;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository {
    void insert(RagDocument document);
    List<RagDocument.Summary> list(String owner, int limit, int offset);
    Optional<RagDocument> find(String owner, UUID id);
}
