package com.github.vladsaraykin.aichat.agent.infrastructure;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JTokkitTokenCounterTest {
    @Test void countsTextWithTheEncodingUsedByGpt41() {
        var counter = new JTokkitTokenCounter();
        assertThat(counter.count("gpt-4.1-mini", "hello world")).isEqualTo(2);
        assertThat(counter.count("gpt-4.1-mini", "")).isZero();
    }
}
