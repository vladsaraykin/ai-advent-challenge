package com.github.vladsaraykin.aichat.agent.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MemoryParsingTest {
    @Test void matchesWhitespaceLayoutButKeepsOriginalSourceQuote() {
        String source = "Команда — два разработчика. В\nцелом я предпочитаю краткие ответы и минимум зависимостей.";
        assertThat(ConfiguredAgent.sourceQuote(source, "В целом я предпочитаю краткие ответы"))
                .isEqualTo("В\nцелом я предпочитаю краткие ответы");
        assertThat(ConfiguredAgent.sourceQuote("я\u00a0предпочитаю  кратко", "я предпочитаю кратко"))
                .isEqualTo("я\u00a0предпочитаю  кратко");
        assertThatThrownBy(() -> ConfiguredAgent.sourceQuote(source, "предпочитаю подробные ответы"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unwrapsOnlyCompleteJsonFenceAndDoesNotRepairBrokenContent() {
        assertThat(ConfiguredAgent.memoryJson("```json\n{\"task\":{}}\n```" )).isEqualTo("{\"task\":{}}");
        assertThat(ConfiguredAgent.memoryJson("```\n{}\n```" )).isEqualTo("{}");
        assertThat(ConfiguredAgent.memoryJson("Пояснение\n```json\n{}\n```" )).startsWith("Пояснение");
        assertThat(ConfiguredAgent.memoryJson("```json\n{\"task\":" )).startsWith("```");
    }
}
