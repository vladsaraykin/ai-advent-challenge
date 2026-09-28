package com.github.vladsaraykin.aichat.rag.api;

import com.github.vladsaraykin.aichat.rag.application.IndexingService;
import com.github.vladsaraykin.aichat.rag.domain.*;
import java.security.Principal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rag")
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
public class IndexController {
    private final IndexingService indexing;
    public IndexController(IndexingService indexing) { this.indexing = indexing; }
    public record StartRequest(DocumentChunk.Strategy strategy) { }

    @PostMapping("/documents/{document}/indexes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IndexRun start(Principal principal, @PathVariable UUID document, @RequestBody StartRequest request) {
        return indexing.start(principal.getName(), document, request.strategy());
    }
    @GetMapping("/documents/{document}/indexes")
    public List<IndexRun> list(Principal principal, @PathVariable UUID document) {
        return indexing.list(principal.getName(), document);
    }
    @GetMapping("/indexes/{run}")
    public IndexRun get(Principal principal, @PathVariable UUID run) { return indexing.get(principal.getName(), run); }
    @GetMapping("/indexes/{run}/chunks")
    public List<DocumentChunk> chunks(Principal principal, @PathVariable UUID run,
                                     @RequestParam(defaultValue = "0") int offset,
                                     @RequestParam(defaultValue = "20") int limit) {
        return indexing.chunks(principal.getName(), run, offset, limit);
    }
}
