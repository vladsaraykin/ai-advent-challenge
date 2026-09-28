package com.github.vladsaraykin.aichat.rag.api;

import com.github.vladsaraykin.aichat.rag.application.DocumentService;
import com.github.vladsaraykin.aichat.rag.application.RagFailure;
import com.github.vladsaraykin.aichat.rag.domain.ExtractedText;
import com.github.vladsaraykin.aichat.rag.domain.RagDocument;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/rag/documents")
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
public class DocumentController {
    private final DocumentService service;
    public DocumentController(DocumentService service) { this.service = service; }
    public record DocumentView(RagDocument.Summary document, String text, ExtractedText.Metadata metadata) { }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentView upload(Principal principal, @RequestPart("file") List<MultipartFile> files) {
        if (files.size() != 1) throw new RagFailure(RagFailure.Kind.INVALID, "Загрузите один файл за запрос.");
        var file = files.getFirst();
        try (var input = file.getInputStream()) {
            return view(service.upload(principal.getName(), file.getOriginalFilename(), input));
        } catch (IOException exception) { throw RagFailure.storage(); }
    }

    @GetMapping
    public List<RagDocument.Summary> list(Principal principal,
                                        @RequestParam(defaultValue = "20") int limit,
                                        @RequestParam(defaultValue = "0") int offset) {
        return service.list(principal.getName(), limit, offset);
    }

    @GetMapping("/{id}")
    public DocumentView get(Principal principal, @PathVariable UUID id) {
        return view(service.get(principal.getName(), id));
    }

    private DocumentView view(RagDocument document) {
        return new DocumentView(document.summary(), document.extracted().text(), document.extracted().metadata());
    }
}
