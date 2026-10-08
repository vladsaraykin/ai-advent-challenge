package com.github.vladsaraykin.aichat.rag.infrastructure;

import java.net.URI;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RagConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RagConfiguration.class);

    @Test void postgresIsRequiredEvenWhenRagIsDisabled() {
        runner.run(context -> {
            assertThat(context).hasFailed();
        });
        runner.withPropertyValues("app.rag.enabled=false").run(context ->
                assertThat(context).hasFailed());
    }

    @Test void enabledRequiresCredentialsBeforeConnecting() {
        runner.withPropertyValues("app.rag.enabled=true").run(context ->
                assertThat(context).hasFailed());
    }

    @Test void validatesLimitsAndRedactsDatabaseCredentials() {
        var database = database("jdbc:postgresql://localhost:15432/ai_advent_rag", 3);
        assertThat(database.toString()).doesNotContain("test-secret", "localhost", "test-user");
        assertThatThrownBy(() -> database("jdbc:mysql://localhost/db", 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> database("jdbc:postgresql://localhost/db?password=secret", 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(database("jdbc:postgresql://localhost/db", 50).maximumPoolSize()).isEqualTo(50);
        assertThatThrownBy(() -> database("jdbc:postgresql://localhost/db", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> database("jdbc:postgresql://localhost/db", 51)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagProperties.Database("jdbc:postgresql://localhost/db", "user", "", 3,
                Duration.ofSeconds(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ollama("file:///tmp/model", 768)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ollama("http://user:secret@localhost:11434", 768)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ollama("http://localhost:11434", 1024)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ollama("http://localhost:11434", 768).dimensions()).isEqualTo(768);
    }

    @Test void flywayIsRestrictedToRagAndCannotCleanOrBaselineExistingTables() {
        var config = new RagConfiguration().ragFlyway(mock(DataSource.class)).getConfiguration();
        assertThat(config.getDefaultSchema()).isEqualTo("rag");
        assertThat(config.getSchemas()).containsExactly("rag");
        assertThat(config.getLocations()[0].getDescriptor()).isEqualTo("classpath:db/rag/migration");
        assertThat(config.isCleanDisabled()).isTrue();
        assertThat(config.isBaselineOnMigrate()).isFalse();
    }

    private RagProperties.Database database(String url, int pool) {
        return new RagProperties.Database(url, "test-user", "test-secret", pool, Duration.ofSeconds(5));
    }

    private RagProperties.Ollama ollama(String url, int dimensions) {
        return new RagProperties.Ollama(URI.create(url), "embeddinggemma", dimensions,
                Duration.ofSeconds(5), Duration.ofSeconds(120));
    }
}
