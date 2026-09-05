package com.github.vladsaraykin.aichat.modelcomparison.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelComparisonRequestTest {
    @Test
    void validatesPromptAndTokenLimit() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new ModelComparisonRequest(" ", 10, null))).hasSize(3);
            assertThat(validator.validate(new ModelComparisonRequest("task", 1_000,
                    List.of("gpt-4o-mini", "gpt-5-mini", "gpt-5.6-sol")))).isEmpty();
            assertThat(validator.validate(new ModelComparisonRequest("task", 32_768,
                    List.of("gpt-4o-mini", "gpt-5-mini", "gpt-5.6-sol")))).isEmpty();
            assertThat(validator.validate(new ModelComparisonRequest("task", 32_769,
                    List.of("gpt-4o-mini", "gpt-5-mini", "gpt-5.6-sol")))).singleElement()
                    .extracting(violation -> violation.getMessage()).isEqualTo("Максимум 32768 токенов");
        }
    }
}
