package com.github.vladsaraykin.aichat.rag.infrastructure;

import java.util.UUID;
import java.util.List;
import java.nio.file.Path;
import java.time.Instant;
import com.github.vladsaraykin.aichat.rag.domain.*;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** Opt-in: use a disposable database with public.vector already installed, never production. */
@EnabledIfEnvironmentVariable(named = "RAG_TEST_DATABASE_URL", matches = ".+")
class RagPostgresTest {
    @TempDir Path directory;

    @Test void cosineSearchAndQuestionPersistenceAreOwnerAndIndexScoped() {
        var ds = new DriverManagerDataSource(System.getenv("RAG_TEST_DATABASE_URL"),
                System.getenv("RAG_TEST_DATABASE_USERNAME"), System.getenv("RAG_TEST_DATABASE_PASSWORD"));
        var config = new RagConfiguration(); config.ragFlyway(ds).migrate();
        var jdbc = new JdbcTemplate(ds);
        var indexes = new JdbcIndexRepository(jdbc, config.ragTransactions(ds));
        var questions = new JdbcRagQuestionRepository(jdbc);
        String owner = "test-" + UUID.randomUUID();
        var document = new RagDocument(UUID.randomUUID(), owner, "test.pdf", "application/pdf", UUID.randomUUID(), 1,
                "c".repeat(64), new ExtractedText("ab", new ExtractedText.Metadata("pdf",1,List.of(),List.of())),Instant.now());
        new JdbcDocumentRepository(jdbc).insert(document);
        try {
            var id = indexes.create(document,DocumentChunk.Strategy.FIXED_SIZE,new ChunkingSettings(384,64),"embeddinggemma");
            var first = new DocumentChunk(UUID.randomUUID(),0,"test.pdf","test.pdf","section",1,1,0,1,"a",1);
            var second = new DocumentChunk(UUID.randomUUID(),1,"test.pdf","test.pdf","section",1,1,1,2,"b",1);
            indexes.prepare(owner,id,List.of(first,second));
            var x = new float[768]; x[0]=1;
            var y = new float[768]; y[1]=1;
            indexes.saveEmbedding(owner,id,0,new com.github.vladsaraykin.aichat.rag.application.EmbeddingModel.Result(x,1L));
            indexes.saveEmbedding(owner,id,1,new com.github.vladsaraykin.aichat.rag.application.EmbeddingModel.Result(y,1L));
            assertThat(questions.search(owner,id,x,5)).isEmpty();
            indexes.finish(owner,id,1,null);
            var sources = questions.search(owner,id,x,2);
            assertThat(sources).hasSize(2);
            assertThat(sources.getFirst().chunk().content()).isEqualTo("a");
            assertThat(sources.getFirst().similarity()).isCloseTo(1,within(.0001));
            assertThat(sources.getLast().similarity()).isCloseTo(0,within(.0001));
            assertThat(questions.search("other",id,x,2)).isEmpty();
            assertThat(questions.search(owner,UUID.randomUUID(),x,2)).isEmpty();
            assertThat(questions.searchAll(owner,"embeddinggemma:latest",x,10)).hasSize(2);
            assertThat(questions.searchAll("other","embeddinggemma",x,10)).isEmpty();
            assertThat(questions.searchAll(owner,"different-model",x,10)).isEmpty();
            var structural=indexes.create(document,DocumentChunk.Strategy.STRUCTURAL,new ChunkingSettings(384,64),"embeddinggemma");
            var structuralChunk=new DocumentChunk(UUID.randomUUID(),0,"test.pdf","test","structural",1,1,0,2,"ab",1);
            indexes.prepare(owner,structural,List.of(structuralChunk));
            indexes.saveEmbedding(owner,structural,0,new com.github.vladsaraykin.aichat.rag.application.EmbeddingModel.Result(x,1L));
            indexes.finish(owner,structural,1,null);
            assertThat(questions.searchAll(owner,"embeddinggemma",x,10)).extracting(s->s.chunk().section()).containsExactly("structural");
            var q = new RagQuestion(UUID.randomUUID(),id,"question",RagQuestion.Mode.BOTH,"RUNNING",List.of(),Instant.now());
            assertThat(questions.create(owner,q)).isTrue();
            assertThat(questions.create(owner,q)).isFalse();
            assertThat(questions.find("other",q.id())).isEmpty();
            var answer = new RagQuestion.Answer(RagQuestion.Mode.WITH_RAG,"answer [1]",sources,null,null);
            var done = new RagQuestion(q.id(),id,"question",q.mode(),"COMPLETED",List.of(answer),q.createdAt());
            questions.save(owner,done);
            assertThat(new JdbcRagQuestionRepository(jdbc).find(owner,q.id())).contains(done);
            questions.recoverInterrupted();
            assertThat(questions.find(owner,q.id())).contains(done);
            questions.save(owner,q); questions.recoverInterrupted();
            assertThat(questions.find(owner,q.id()).orElseThrow().status()).isEqualTo("INTERRUPTED");
        } finally { jdbc.update("DELETE FROM rag.documents WHERE owner_username=? AND id=?",owner,document.id()); }
    }

    @Test void indexesPersistVectorsProgressRetryIsolationAndRestartRecovery() {
        var dataSource = new DriverManagerDataSource(System.getenv("RAG_TEST_DATABASE_URL"),
                System.getenv("RAG_TEST_DATABASE_USERNAME"), System.getenv("RAG_TEST_DATABASE_PASSWORD"));
        var config = new RagConfiguration();
        config.ragFlyway(dataSource).migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var repository = new JdbcIndexRepository(jdbc, config.ragTransactions(dataSource));
        String owner = "test-" + UUID.randomUUID();
        var document = new RagDocument(UUID.randomUUID(), owner, "test.pdf", "application/pdf", UUID.randomUUID(),
                5, "b".repeat(64), new ExtractedText("Текст", new ExtractedText.Metadata("pdf", 1, List.of(), List.of())), Instant.now());
        new JdbcDocumentRepository(jdbc).insert(document);
        try {
            var chunk = new DocumentChunk(UUID.randomUUID(), 0, "test.pdf", "test.pdf", "Section", 1, 1, 0, 5, "Текст", 3);
            UUID first = repository.create(document, DocumentChunk.Strategy.FIXED_SIZE, new ChunkingSettings(384, 64), "embeddinggemma");
            repository.prepare(owner, first, List.of(chunk));
            assertThatThrownBy(() -> repository.get("other", first)).isInstanceOf(com.github.vladsaraykin.aichat.rag.application.RagFailure.class);
            assertThat(repository.chunks("other", first, 0, 20)).isEmpty();
            assertThatThrownBy(() -> repository.saveEmbedding("other", first, 0,
                    new com.github.vladsaraykin.aichat.rag.application.EmbeddingModel.Result(new float[768], 8L))).isInstanceOf(RuntimeException.class);
            assertThat(repository.get(owner, first).embeddedChunks()).isZero();
            repository.saveEmbedding(owner, first, 0,
                    new com.github.vladsaraykin.aichat.rag.application.EmbeddingModel.Result(new float[768], 8L));
            assertThatThrownBy(() -> repository.saveEmbedding(owner, first, 0,
                    new com.github.vladsaraykin.aichat.rag.application.EmbeddingModel.Result(new float[768], 8L))).isInstanceOf(RuntimeException.class);
            repository.finish(owner, first, 100, null);
            assertThat(repository.get(owner, first).promptTokens()).isEqualTo(8);
            assertThat(repository.get(owner, first).status()).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT public.vector_dims(embedding) FROM rag.chunks WHERE run_id=?", Integer.class, first)).isEqualTo(768);
            UUID second = repository.create(document, DocumentChunk.Strategy.FIXED_SIZE, new ChunkingSettings(384, 64), "embeddinggemma");
            repository.prepare(owner, second, List.of(chunk));
            assertThat(repository.chunks(owner, second, 0, 20).getFirst().chunkId())
                    .isNotEqualTo(repository.chunks(owner, first, 0, 20).getFirst().chunkId());
            var restarted = new JdbcIndexRepository(jdbc, config.ragTransactions(dataSource));
            restarted.recoverInterrupted();
            assertThat(restarted.get(owner, second).errorCode()).isEqualTo("INTERRUPTED");
            assertThat(restarted.get(owner, first).status()).isEqualTo("COMPLETED");
            assertThat(restarted.chunks(owner, first, 0, 1).getFirst().pageStart()).isEqualTo(1);
            assertThat(restarted.list("other", document.id())).isEmpty();
            assertThat(restarted.list(owner, document.id())).hasSize(2);
        } finally { jdbc.update("DELETE FROM rag.documents WHERE owner_username=? AND id=?", owner, document.id()); }
    }

    @Test void documentRepositoryRoundTripsMetadataAndIsolatesUsersAfterRestart() {
        var dataSource = new DriverManagerDataSource(System.getenv("RAG_TEST_DATABASE_URL"),
                System.getenv("RAG_TEST_DATABASE_USERNAME"), System.getenv("RAG_TEST_DATABASE_PASSWORD"));
        new RagConfiguration().ragFlyway(dataSource).migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var repo = new JdbcDocumentRepository(jdbc);
        String owner = "test-" + UUID.randomUUID();
        var extracted = new ExtractedText("Привет 😀", new ExtractedText.Metadata("pdf", 1,
                List.of(new ExtractedText.Block("PAGE", "", 1, 0, 9)), List.of("No OCR")));
        var document = new RagDocument(UUID.randomUUID(), owner, "test.pdf", "application/pdf", UUID.randomUUID(),
                123, "a".repeat(64), extracted, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        try {
            repo.insert(document);
            assertThat(new JdbcDocumentRepository(jdbc).find(owner, document.id())).contains(document);
            assertThat(repo.list(owner, 20, 0)).containsExactly(document.summary());
            assertThat(repo.list(owner, 20, 1)).isEmpty();
            assertThat(repo.find("other-owner", document.id())).isEmpty();
            assertThat(repo.list("other-owner", 20, 0)).isEmpty();
        } finally { jdbc.update("DELETE FROM rag.documents WHERE owner_username=? AND id=?", owner, document.id()); }
    }
    @Test void enabledConfigurationBindsYamlMigratesAndClosesPool() throws Exception {
        var yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        var pool = new java.util.concurrent.atomic.AtomicReference<HikariDataSource>();
        new ApplicationContextRunner().withUserConfiguration(RagConfiguration.class)
                .withInitializer(context -> yaml.forEach(source -> context.getEnvironment().getPropertySources().addLast(source)))
                .withPropertyValues("app.rag.enabled=true",
                        "app.rag.documents-directory=" + directory.toRealPath(),
                        "app.rag.database.url=" + System.getenv("RAG_TEST_DATABASE_URL"),
                        "app.rag.database.username=" + System.getenv("RAG_TEST_DATABASE_USERNAME"),
                        "app.rag.database.password=" + System.getenv("RAG_TEST_DATABASE_PASSWORD"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    pool.set(context.getBean("ragDataSource", HikariDataSource.class));
                    assertThat(pool.get().getMaximumPoolSize()).isEqualTo(3);
                    assertThat(context.getBean(RagProperties.class).ollama().dimensions()).isEqualTo(768);
                    assertThat(context.getBean("ragJdbcTemplate", JdbcTemplate.class)
                            .queryForObject("SELECT count(*) FROM rag.flyway_schema_history WHERE version='1' AND success", Integer.class))
                            .isEqualTo(1);
                });
        assertThat(pool.get().isClosed()).isTrue();
    }

    @Test void migrationIsRepeatableAndEnforcesOwnershipDimensionsAndRunConstraints() {
        var dataSource = new DriverManagerDataSource(System.getenv("RAG_TEST_DATABASE_URL"),
                System.getenv("RAG_TEST_DATABASE_USERNAME"), System.getenv("RAG_TEST_DATABASE_PASSWORD"));
        var flyway = new RagConfiguration().ragFlyway(dataSource);
        flyway.migrate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        var jdbc = new JdbcTemplate(dataSource);
        UUID doc = UUID.randomUUID(), run = UUID.randomUUID();
        String owner = "test-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rag.documents (id, owner_username, original_filename, media_type, storage_key, byte_size, sha256)
                VALUES (?, ?, 'test.pdf', 'application/pdf', ?, 100, ?)
                """, doc, owner, UUID.randomUUID(), "a".repeat(64));
        try {
            assertThatThrownBy(() -> insertRun(jdbc, UUID.randomUUID(), doc, "other-user"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            insertRun(jdbc, run, doc, owner);
            String vector = "[" + "0,".repeat(767) + "1]";
            jdbc.update("""
                    INSERT INTO rag.chunks (id, owner_username, run_id, ordinal, source, title, content,
                        start_offset, end_offset, embedding)
                    VALUES (?, ?, ?, 0, 'test.pdf', 'Test', 'Hello', 0, 5, ?::public.vector)
                    """, UUID.randomUUID(), owner, run, vector);
            assertThat(jdbc.queryForObject("SELECT public.vector_dims(embedding) FROM rag.chunks WHERE owner_username=? AND run_id=?",
                    Integer.class, owner, run)).isEqualTo(768);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM rag.chunks WHERE owner_username=? AND run_id=?",
                    Integer.class, "other-user", run)).isZero();
            assertThatThrownBy(() -> jdbc.update("UPDATE rag.chunks SET embedding='[1,2,3]'::public.vector WHERE run_id=?", run))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE rag.chunks SET owner_username='other-user' WHERE run_id=?", run))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE rag.index_runs SET status='COMPLETED', finished_at=now() WHERE id=?", run))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            jdbc.update("UPDATE rag.index_runs SET total_chunks=1, embedded_chunks=1, status='COMPLETED', finished_at=now() WHERE id=?", run);
            UUID failed = UUID.randomUUID();
            insertRun(jdbc, failed, doc, owner);
            jdbc.update("UPDATE rag.index_runs SET status='FAILED', finished_at=now(), error_code='OLLAMA_UNAVAILABLE' WHERE id=?", failed);
            assertThat(jdbc.queryForObject("SELECT status FROM rag.index_runs WHERE owner_username=? AND id=?", String.class, owner, run))
                    .isEqualTo("COMPLETED");
        } finally {
            // Only this test's document and cascading children; never drop schemas or clean Flyway.
            jdbc.update("DELETE FROM rag.documents WHERE owner_username=? AND id=?", owner, doc);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rag.chunks WHERE owner_username=?", Integer.class, owner)).isZero();
    }

    private void insertRun(JdbcTemplate jdbc, UUID id, UUID document, String owner) {
        jdbc.update("""
                INSERT INTO rag.index_runs (id, owner_username, document_id, strategy, embedding_model,
                    embedding_dimensions, chunking_parameters)
                VALUES (?, ?, ?, 'FIXED_SIZE', 'embeddinggemma', 768, '{"size":512,"overlap":64}'::jsonb)
                """, id, owner, document);
    }
}
