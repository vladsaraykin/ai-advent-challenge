package com.github.vladsaraykin.aichat.rag.infrastructure;

import com.github.vladsaraykin.aichat.rag.application.*;
import com.github.vladsaraykin.aichat.rag.domain.*;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in: real Ollama, but only a tiny generated document and a disposable database. */
@EnabledIfEnvironmentVariable(named = "RAG_TEST_DATABASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAG_TEST_OLLAMA_BASE_URL", matches = ".+")
class ExternalIndexingTest {
    @TempDir Path directory;

    @Test void uploadChunkEmbedAndPersistBothStrategies() throws Exception {
        var dataSource = new DriverManagerDataSource(System.getenv("RAG_TEST_DATABASE_URL"),
                System.getenv("RAG_TEST_DATABASE_USERNAME"), System.getenv("RAG_TEST_DATABASE_PASSWORD"));
        var config = new RagConfiguration();
        config.ragFlyway(dataSource).migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var repository = new JdbcIndexRepository(jdbc, config.ragTransactions(dataSource));
        var documents = new DocumentService(new JdbcDocumentRepository(jdbc),
                new LocalDocumentStore(directory.toRealPath()), new OfficeDocumentExtractor());
        String owner = "test-" + UUID.randomUUID().toString().substring(0, 20);
        var document = documents.upload(owner, "integration.docx", new ByteArrayInputStream(wordDocument()));
        var settings = new ChunkingSettings(384, 64);
        var encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        var model = new OllamaEmbeddingModel(new RagProperties.Ollama(URI.create(System.getenv("RAG_TEST_OLLAMA_BASE_URL")),
                "embeddinggemma", 768, Duration.ofSeconds(5), Duration.ofSeconds(30)));
        try (var service = new IndexingService(documents, new DocumentChunker(encoding::countTokensOrdinary, settings),
                settings, repository, model, "embeddinggemma")) {
            for (var strategy : DocumentChunk.Strategy.values()) {
                var run = service.start(owner, document.id(), strategy);
                long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
                while (!run.status().equals("COMPLETED") && !run.status().equals("FAILED") && System.nanoTime() < deadline) {
                    Thread.sleep(200);
                    run = service.get(owner, run.id());
                }
                assertThat(run.status()).describedAs("run failure: %s", run.errorCode()).isEqualTo("COMPLETED");
                assertThat(run.embeddedChunks()).isEqualTo(strategy == DocumentChunk.Strategy.FIXED_SIZE ? 1 : 2);
                assertThat(run.promptTokens()).isPositive();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM rag.chunks WHERE owner_username=? AND run_id=? "
                        + "AND public.vector_dims(embedding)=768", Integer.class, owner, run.id())).isEqualTo(run.totalChunks());
                assertThat(service.chunks(owner, run.id(), 0, 20)).hasSize(run.totalChunks());
                System.out.printf("Real indexing: strategy=%s chunks=%d tokens=%d durationMs=%d%n",
                        strategy, run.totalChunks(), run.promptTokens(), run.durationMs());
            }
        } finally {
            model.close();
            jdbc.update("DELETE FROM rag.documents WHERE owner_username=? AND id=?", owner, document.id());
        }
    }

    private byte[] wordDocument() throws Exception {
        try (var document = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            var style = CTStyle.Factory.newInstance();
            style.setStyleId("Heading1"); style.addNewName().setVal("Heading 1");
            document.createStyles().addStyle(new XWPFStyle(style));
            var first = document.createParagraph(); first.setStyle("Heading1"); first.createRun().setText("Требования");
            document.createParagraph().createRun().setText("Сервис уведомлений использует Java и PostgreSQL. Отправляем email.");
            var second = document.createParagraph(); second.setStyle("Heading1"); second.createRun().setText("Решения");
            document.createParagraph().createRun().setText("Повторяем временные ошибки. Секреты не записываем в журнал.");
            document.write(bytes);
            return bytes.toByteArray();
        }
    }
}
