package com.github.vladsaraykin.aichat.rag.api;

import com.github.vladsaraykin.aichat.rag.application.ChunkingService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rag/documents")
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
public class ChunkingController {
    private final ChunkingService service;
    public ChunkingController(ChunkingService service) { this.service = service; }

    @GetMapping("/{id}/chunking")
    public ChunkingService.Preview preview(Principal principal, @PathVariable UUID id,
                                          @RequestParam(defaultValue = "0") int offset,
                                          @RequestParam(defaultValue = "20") int limit) {
        return service.preview(principal.getName(), id, offset, limit);
    }
}
