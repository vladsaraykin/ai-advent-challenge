package com.github.vladsaraykin.aichat.temperature.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.vladsaraykin.aichat.temperature.application.GenerationCommand;
import org.junit.jupiter.api.Test;

class OpenAiGenerationClientTest {

    @Test
    void mapsEveryExperimentalParameterToOpenAiOptions() {
        var options = OpenAiGenerationClient.optionsFor(
                new GenerationCommand("same prompt", "gpt-4.1-mini", 1_000, 0.7));

        assertThat(options.getModel()).isEqualTo("gpt-4.1-mini");
        assertThat(options.getTemperature()).isEqualTo(0.7);
        assertThat(options.getMaxTokens()).isEqualTo(1_000);
        assertThat(options.getMaxCompletionTokens()).isNull();
        assertThat(options.getTopP()).isNull();
    }
}
