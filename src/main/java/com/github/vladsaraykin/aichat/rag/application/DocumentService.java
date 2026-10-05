package com.github.vladsaraykin.aichat.rag.application;

import com.github.vladsaraykin.aichat.rag.domain.RagDocument;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentService {
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private final DocumentRepository repository;
    private final DocumentStore store;
    private final DocumentExtractor extractor;
    // One parser at a time per JVM. No unbounded executor/queue on the 4 GB VM.
    private final Semaphore extractionSlot = new Semaphore(1);

    public DocumentService(DocumentRepository repository, DocumentStore store, DocumentExtractor extractor) {
        this.repository = repository; this.store = store; this.extractor = extractor;
    }

    public RagDocument upload(String owner, String filename, InputStream input) {
        validateOwner(owner);
        if (filename == null || filename.isBlank() || filename.length() > 255
                || filename.contains("/") || filename.contains("\\") || filename.chars().anyMatch(Character::isISOControl)) {
            throw new RagFailure(RagFailure.Kind.INVALID, "Укажите имя файла без пути, длиной до 255 символов.");
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String mediaType = switch (extension) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> throw new RagFailure(RagFailure.Kind.UNSUPPORTED, "Поддерживаются только PDF, DOC и DOCX.");
        };
        if (!extractionSlot.tryAcquire()) {
            throw new RagFailure(RagFailure.Kind.BUSY, "Другой документ уже обрабатывается. Повторите загрузку позже.");
        }
        var started = System.nanoTime();
        DocumentStore.Stored stored = null;
        boolean persistenceAttempted = false;
        try {
            stored = store.save(input);
            var extracted = extractor.extract(stored.path(), extension);
            var document = new RagDocument(UUID.randomUUID(), owner, filename, mediaType, stored.key(),
                    stored.size(), stored.sha256(), extracted, Instant.now());
            persistenceAttempted = true;
            repository.insert(document);
            log.info("rag_document_uploaded documentId={} bytes={} characters={} durationMs={}", document.id(),
                    document.byteSize(), extracted.text().length(), (System.nanoTime() - started) / 1_000_000);
            return document;
        } catch (RuntimeException exception) {
            // A lost DB connection can leave commit outcome unknown. Retain the file in that case.
            if (stored != null && !persistenceAttempted) store.discard(stored);
            log.warn("rag_document_upload_failed errorType={} fileRetained={}", exception.getClass().getSimpleName(),
                    stored != null && persistenceAttempted);
            if (exception instanceof RagFailure failure) throw failure;
            throw RagFailure.storage();
        } finally {
            try {
                if (stored != null) store.release(stored);
            } finally {
                extractionSlot.release();
            }
        }
    }

    public List<RagDocument.Summary> list(String owner, int limit, int offset) {
        validateOwner(owner);
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new RagFailure(RagFailure.Kind.INVALID, "limit должен быть от 1 до 100, offset — неотрицательным.");
        }
        return repository.list(owner, limit, offset);
    }

    public RagDocument get(String owner, UUID id) {
        validateOwner(owner);
        return repository.find(owner, id).orElseThrow(() ->
                new RagFailure(RagFailure.Kind.NOT_FOUND, "Документ не найден."));
    }

    private static void validateOwner(String owner) {
        if (owner == null || !owner.matches("[a-z0-9][a-z0-9._-]{2,39}")) {
            throw new RagFailure(RagFailure.Kind.NOT_FOUND, "Пользователь не найден.");
        }
    }
}
