package com.github.vladsaraykin.aichat.rag.application;

import com.github.vladsaraykin.aichat.rag.domain.DocumentChunk;
import com.github.vladsaraykin.aichat.rag.infrastructure.ProjectDocumentSyncRepository;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Synchronizes allowlisted documentation and optionally source code into the owner's RAG. */
@Service
@ConditionalOnProperty(prefix = "app.rag", name = {"enabled", "project-docs.enabled"},
        havingValue = "true", matchIfMissing = true)
public class ProjectDocumentationSyncService {
    private static final Logger log = LoggerFactory.getLogger(ProjectDocumentationSyncService.class);
    private static final long MAX_TOTAL_BYTES = 20L * 1024 * 1024;
    private final Path root;
    private final String owner;
    private final DocumentService documents;
    private final ProjectDocumentIndexer indexing;
    private final ProjectDocumentSyncRepository manifest;
    private final boolean includeCode;
    private final AtomicBoolean running = new AtomicBoolean();

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectDocumentationSyncService(
            @org.springframework.beans.factory.annotation.Value("${app.rag.project-docs.root:.}") Path root,
            @org.springframework.beans.factory.annotation.Value("${app.rag.project-docs.owner:vlad}") String owner,
            DocumentService documents, ProjectDocumentIndexer indexing, ProjectDocumentSyncRepository manifest,
            @org.springframework.beans.factory.annotation.Value("${app.rag.project-docs.include-code:false}") boolean includeCode) {
        this.root = root.toAbsolutePath().normalize();
        this.owner = owner;
        this.documents = documents;
        this.indexing = indexing;
        this.manifest = manifest;
        this.includeCode = includeCode;
    }

    public ProjectDocumentationSyncService(Path root, String owner, DocumentService documents,
            ProjectDocumentIndexer indexing, ProjectDocumentSyncRepository manifest) {
        this(root, owner, documents, indexing, manifest, false);
    }

    @Scheduled(initialDelayString = "${app.rag.project-docs.initial-delay-ms:10000}",
            fixedDelayString = "${app.rag.project-docs.interval-ms:300000}")
    public void synchronize() {
        if (!running.compareAndSet(false, true)) return;
        try { reconcile(); }
        catch (RuntimeException error) {
            Throwable cause = error;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            String causeMessage = cause.getMessage() == null ? "" : cause.getMessage().replaceAll("[\\r\\n\\t]", " ");
            if (causeMessage.length() > 240) causeMessage = causeMessage.substring(0, 240);
            log.warn("project_docs_sync_failed root={} owner={} errorType={} causeType={} causeMessage={}", root, owner,
                    error.getClass().getSimpleName(), cause.getClass().getSimpleName(), causeMessage);
        } finally { running.set(false); }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void synchronizeOnStartup() {
        synchronize();
    }

    private void reconcile() {
        var files = scan(); // A failed/partial scan must never trigger stale-document deletion.
        log.info("project_docs_sync_started root={} owner={} sourceCount={}", root, owner, files.size());
        var entries = manifest.list(owner).stream().collect(Collectors.toMap(ProjectDocumentSyncRepository.Entry::path, e -> e));
        for (var entry : entries.values()) completePending(entry);
        entries = manifest.list(owner).stream().collect(Collectors.toMap(ProjectDocumentSyncRepository.Entry::path, e -> e));

        for (var file : files.values()) {
            var entry = entries.get(file.path());
            if (entry != null && entry.pendingDocument() != null) continue;
            if (entry != null && file.sha().equals(entry.activeSha()) && entry.activeDocument() != null
                    && documents.exists(owner, entry.activeDocument())) continue;
            var safeName = storageName(file.path());
            var uploaded = documents.upload(owner, safeName, new ByteArrayInputStream(file.content()));
            try {
                var run = indexing.start(owner, uploaded.id(), DocumentChunk.Strategy.STRUCTURAL);
                manifest.pending(owner, file.path(), file.sha(), uploaded.id(), run.id());
                log.info("project_docs_sync_queued pathHash={} documentId={} runId={}", shortHash(file.path()), uploaded.id(), run.id());
            } catch (RuntimeException error) {
                documents.deleteIfPresent(owner, uploaded.id());
                if (error instanceof RagFailure failure && failure.kind() == RagFailure.Kind.BUSY) {
                    log.info("project_docs_sync_deferred pathHash={} reason=index_queue_busy", shortHash(file.path()));
                    return;
                }
                throw error;
            }
        }

        Set<String> current = files.keySet();
        for (var entry : manifest.list(owner)) {
            if (current.contains(entry.path())) continue;
            // Turning code indexing off is not authorization to delete existing code documents.
            if (!includeCode && !entry.path().equals("README.md") && !entry.path().startsWith("project/docs/")) continue;
            if (entry.pendingRun() != null) continue; // Do not remove a document while its indexing worker is active.
            for (var id : java.util.stream.Stream.of(entry.activeDocument(), entry.pendingDocument()).filter(java.util.Objects::nonNull).toList()) {
                documents.deleteIfPresent(owner, id); // Keep the manifest if PostgreSQL deletion fails, so next sync retries.
            }
            manifest.remove(owner, entry.path());
        }
    }

    private void completePending(ProjectDocumentSyncRepository.Entry entry) {
        if (entry.pendingRun() == null) return;
        var run = indexing.get(owner, entry.pendingRun());
        if ("COMPLETED".equals(run.status())) {
            if (entry.activeDocument() != null && !entry.activeDocument().equals(entry.pendingDocument())) {
                documents.deleteIfPresent(owner, entry.activeDocument());
            }
            manifest.activate(owner, entry.path(), entry.pendingSha());
        } else if ("FAILED".equals(run.status())) {
            documents.deleteIfPresent(owner, entry.pendingDocument());
            manifest.clearPending(owner, entry.path());
            log.warn("project_docs_index_failed pathHash={} runId={} errorCode={}", shortHash(entry.path()), run.id(), run.errorCode());
        }
    }

    private Map<String, SourceFile> scan() {
        try {
            Path realRoot = root.toRealPath();
            Path readme = realRoot.resolve("README.md");
            var paths = new java.util.ArrayList<Path>();
            if (Files.isSymbolicLink(readme)) throw new IllegalStateException("README symlink is not allowed");
            if (Files.isRegularFile(readme, LinkOption.NOFOLLOW_LINKS)) paths.add(readme);
            Path docs = realRoot.resolve("project/docs");
            if (Files.isSymbolicLink(docs)) throw new IllegalStateException("Documentation directory symlink is not allowed");
            if (Files.isDirectory(docs, LinkOption.NOFOLLOW_LINKS)) {
                try (var stream = Files.walk(docs)) {
                    stream.filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".md"))
                            .forEach(path -> {
                                if (Files.isSymbolicLink(path)) throw new IllegalStateException("Markdown symlink is not allowed");
                                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) paths.add(path);
                            });
                }
            }
            if (includeCode) {
                for (String directory : java.util.List.of("src/main/java", "src/test/java", "src/main/resources/db",
                        "frontend/src", "mcp/project-git", "mcp/project-files")) {
                    Path sourceRoot = realRoot.resolve(directory);
                    Path currentPath = realRoot;
                    for (Path segment : realRoot.relativize(sourceRoot)) {
                        currentPath = currentPath.resolve(segment);
                        if (Files.isSymbolicLink(currentPath)) throw new IllegalStateException("Source directory symlink is not allowed");
                    }
                    if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) continue;
                    try (var walk = Files.walk(sourceRoot, 32)) {
                        for (Path path : walk.toList()) {
                            if (Files.isSymbolicLink(path)) throw new IllegalStateException("Source symlink is not allowed");
                            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                    && path.toString().matches(".*\\.(java|js|jsx|mjs|css|sql)$") && Files.size(path) > 0) paths.add(path);
                        }
                    }
                }
            }
            var result = new java.util.TreeMap<String, SourceFile>();
            long bytesTotal = 0;
            for (Path path : paths) {
                Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
                if (!real.startsWith(realRoot) || Files.isSymbolicLink(path)) throw new IllegalStateException("Invalid documentation path");
                long size = Files.size(path);
                bytesTotal += size;
                if (size == 0 || size > 2L * 1024 * 1024 || bytesTotal > MAX_TOTAL_BYTES) {
                    throw new IllegalStateException("Project documentation size limit exceeded");
                }
                String relative = realRoot.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
                byte[] content = Files.readAllBytes(path);
                if (!relative.endsWith(".md")) {
                    String code = new String(content, java.nio.charset.StandardCharsets.UTF_8);
                    content = ("# Source: " + relative + "\n\nRepository snapshot; PR diff is authoritative for changes.\n\n"
                            + code).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                result.put(relative, new SourceFile(relative, sha(content), content));
            }
            if (result.isEmpty()) throw new IllegalStateException("No allowlisted project documentation found");
            return java.util.Collections.unmodifiableSortedMap(result);
        } catch (Exception error) {
            throw new IllegalStateException("Project documentation scan failed", error);
        }
    }

    private static String storageName(String path) {
        String prefix = "project__" + shortHash(path) + "__";
        String flattenedPath = path.replace("/", "__");
        if (!flattenedPath.endsWith(".md")) flattenedPath += ".md";
        return prefix + flattenedPath.substring(0, Math.min(flattenedPath.length() - 3, 252 - prefix.length())) + ".md";
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static String shortHash(String value) { return sha(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 12); }
    private record SourceFile(String path, String sha, byte[] content) { }
}
