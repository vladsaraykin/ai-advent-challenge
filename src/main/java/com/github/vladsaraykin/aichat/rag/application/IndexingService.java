package com.github.vladsaraykin.aichat.rag.application;

import com.github.vladsaraykin.aichat.rag.domain.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Single-JVM worker. A restart marks unfinished attempts failed; retry creates a new run. */
public final class IndexingService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);
    private final DocumentService documents;
    private final DocumentChunker chunker;
    private final ChunkingSettings settings;
    private final IndexRepository repository;
    private final EmbeddingModel embeddings;
    private final String model;
    private final Semaphore slots = new Semaphore(3);
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(3), Thread.ofPlatform().name("rag-indexer").factory(), new ThreadPoolExecutor.AbortPolicy());

    public IndexingService(DocumentService documents, DocumentChunker chunker, ChunkingSettings settings,
                           IndexRepository repository, EmbeddingModel embeddings, String model) {
        this.documents = documents; this.chunker = chunker; this.settings = settings;
        this.repository = repository; this.embeddings = embeddings; this.model = model;
        repository.recoverInterrupted();
    }

    public IndexRun start(String owner, UUID documentId, DocumentChunk.Strategy strategy) {
        if (strategy == null) throw new RagFailure(RagFailure.Kind.INVALID, "Укажите стратегию индексации.");
        var document = documents.get(owner, documentId);
        if (!slots.tryAcquire()) throw new RagFailure(RagFailure.Kind.BUSY, "Очередь индексации заполнена. Повторите позже.");
        UUID id = null;
        try {
            id = repository.create(document, strategy, settings, model);
            var initial = repository.get(owner, id);
            var run = id;
            worker.execute(() -> process(owner, run, documentId, strategy));
            return initial;
        } catch (RuntimeException error) {
            slots.release();
            if (id != null) fail(owner, id, 0, "SCHEDULING_FAILED");
            throw RagFailure.storage();
        }
    }

    private void process(String owner, UUID run, UUID documentId, DocumentChunk.Strategy strategy) {
        long started = System.nanoTime();
        log.info("rag_index_started runId={} documentId={} strategy={}", run, documentId, strategy);
        try {
            var chunks = chunker.split(documents.get(owner, documentId), strategy);
            repository.prepare(owner, run, chunks);
            for (var chunk : chunks) {
                if (Thread.currentThread().isInterrupted()) throw new EmbeddingModel.Failure("INTERRUPTED");
                var result = embeddings.embed(chunk.content());
                repository.saveEmbedding(owner, run, chunk.ordinal(), result);
            }
            repository.finish(owner, run, elapsed(started), null);
            log.info("rag_index_completed runId={} chunks={} durationMs={}", run, chunks.size(), elapsed(started));
        } catch (RuntimeException error) {
            String code = error instanceof EmbeddingModel.Failure failure ? failure.code() : "INDEXING_FAILED";
            fail(owner, run, elapsed(started), code);
        } finally { slots.release(); }
    }

    private void fail(String owner, UUID run, long duration, String code) {
        log.warn("rag_index_failed runId={} code={}", run, code);
        try { repository.finish(owner, run, duration, code); }
        catch (RuntimeException ignored) { log.warn("rag_index_status_write_failed runId={}", run); }
    }

    public IndexRun get(String owner, UUID run) { return repository.get(owner, run); }
    public List<IndexRun> list(String owner, UUID document) {
        documents.get(owner, document);
        return repository.list(owner, document);
    }
    public List<DocumentChunk> chunks(String owner, UUID run, int offset, int limit) {
        repository.get(owner, run);
        if (offset < 0 || limit < 1 || limit > 100) throw new RagFailure(RagFailure.Kind.INVALID, "Некорректная пагинация.");
        return repository.chunks(owner, run, offset, limit);
    }
    private long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    @Override public void close() {
        worker.shutdownNow();
        try { worker.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
