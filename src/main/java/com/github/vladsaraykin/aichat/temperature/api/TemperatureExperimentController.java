package com.github.vladsaraykin.aichat.temperature.api;

import com.github.vladsaraykin.aichat.temperature.application.TemperatureExperimentService;
import com.github.vladsaraykin.aichat.temperature.domain.TemperatureExperiment;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/temperature-experiments")
public class TemperatureExperimentController {
    public static final List<String> MODELS = List.of("gpt-4.1-mini", "gpt-4.1", "gpt-4.1-nano", "gpt-4o", "gpt-4o-mini");
    private final TemperatureExperimentService service;

    public TemperatureExperimentController(TemperatureExperimentService service) { this.service = service; }

    @GetMapping("/models")
    public ExperimentConfig config() {
        return new ExperimentConfig(MODELS, TemperatureExperimentService.TEMPERATURES);
    }

    @PostMapping
    public ResponseEntity<?> run(@Valid @RequestBody TemperatureExperimentRequest request) {
        if (!MODELS.contains(request.model())) {
            return ResponseEntity.badRequest().body(new ApiError("Выбрана неподдерживаемая модель"));
        }
        TemperatureExperiment experiment = service.run(request.prompt().trim(), request.model(), request.maxTokens());
        return ResponseEntity.ok(experiment);
    }

    public record ApiError(String message) { }

    public record ExperimentConfig(List<String> models, List<Double> temperatures) { }
}
