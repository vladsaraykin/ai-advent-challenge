package com.github.vladsaraykin.aichat.temperature.domain;

import java.util.List;

public record TemperatureExperiment(String prompt, String model, int maxTokens,
                                    List<GenerationResult> results, long durationMs) {
    public TemperatureExperiment {
        results = List.copyOf(results);
    }
}
