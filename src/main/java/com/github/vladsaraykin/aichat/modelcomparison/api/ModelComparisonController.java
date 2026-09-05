package com.github.vladsaraykin.aichat.modelcomparison.api;

import com.github.vladsaraykin.aichat.modelcomparison.application.ModelComparisonService;
import com.github.vladsaraykin.aichat.modelcomparison.domain.ModelComparison;
import com.github.vladsaraykin.aichat.modelcomparison.domain.ModelOption;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/model-comparisons")
public class ModelComparisonController {
    private final ModelComparisonService service;

    public ModelComparisonController(ModelComparisonService service) {
        this.service = service;
    }

    @GetMapping("/models")
    public List<ModelOption> models() {
        return ModelOption.CATALOG;
    }

    @PostMapping
    public ModelComparison compare(@Valid @RequestBody ModelComparisonRequest request) {
        return service.compare(request.prompt().trim(), request.maxTokens(), request.models());
    }
}
