package com.github.vladsaraykin.aichat.temperature.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class TemperatureExperimentRequestTest {
    @Test
    void validatesPromptAndTokenRange() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new TemperatureExperimentRequest(" ", "gpt-4.1-mini", 10))).hasSize(2);
            assertThat(validator.validate(new TemperatureExperimentRequest("task", "gpt-4.1-mini", 512))).isEmpty();
        }
    }
}
