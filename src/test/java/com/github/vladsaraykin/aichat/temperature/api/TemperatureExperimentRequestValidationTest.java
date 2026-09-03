package com.github.vladsaraykin.aichat.temperature.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class TemperatureExperimentRequestValidationTest {
    private final jakarta.validation.Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsBlankPromptAndOutOfRangeTokenLimits() {
        assertThat(validator.validate(new TemperatureExperimentRequest("   ", "gpt-4.1-mini", 63)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("prompt", "maxTokens");
        assertThat(validator.validate(new TemperatureExperimentRequest("prompt", "gpt-4.1-mini", 4097)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("maxTokens");
    }
}
