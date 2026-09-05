package com.github.vladsaraykin.aichat.modelcomparison.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.vladsaraykin.aichat.modelcomparison.application.GenerationCommand;
import org.junit.jupiter.api.Test;

class OpenAiGenerationClientTest {
    @Test
    void usesCompletionTokenLimitForGpt56WithoutSamplingOverrides() {
        var options = OpenAiGenerationClient.optionsFor(new GenerationCommand("same", "gpt-5.6-sol", 1_000));
        assertThat(options.getModel()).isEqualTo("gpt-5.6-sol");
        assertThat(options.getMaxCompletionTokens()).isEqualTo(1_000);
        assertThat(options.getMaxTokens()).isNull();
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getTopP()).isNull();
        assertThat(options.getReasoningEffort()).isEqualTo("low");
    }

    @Test
    void doesNotSendUnsupportedReasoningParameterToGpt4() {
        var options = OpenAiGenerationClient.optionsFor(new GenerationCommand("same", "gpt-4.1-mini", 1_000));
        assertThat(options.getReasoningEffort()).isNull();
    }
}
