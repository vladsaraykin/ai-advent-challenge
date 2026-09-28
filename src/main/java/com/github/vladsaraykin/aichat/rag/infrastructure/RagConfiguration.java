package com.github.vladsaraykin.aichat.rag.infrastructure;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import com.github.vladsaraykin.aichat.rag.application.*;
import org.springframework.beans.factory.annotation.Value;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit, RAG-only persistence; no global datasource or Flyway auto-configuration. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({RagProperties.class, com.github.vladsaraykin.aichat.rag.domain.ChunkingSettings.class})
public class RagConfiguration {
    @Bean
    IndexRepository ragIndexRepository(@Qualifier("ragJdbcTemplate") JdbcTemplate jdbc,
                                       @Qualifier("ragTransactions") TransactionTemplate transactions) {
        return new JdbcIndexRepository(jdbc, transactions);
    }

    @Bean(destroyMethod = "close")
    EmbeddingModel ragEmbeddingModel(RagProperties properties) { return new OllamaEmbeddingModel(properties.ollama()); }

    @Bean(destroyMethod = "close")
    IndexingService ragIndexingService(DocumentService documents, DocumentChunker chunker,
            com.github.vladsaraykin.aichat.rag.domain.ChunkingSettings settings,
            IndexRepository repository, EmbeddingModel embeddings, RagProperties properties) {
        return new IndexingService(documents, chunker, settings, repository, embeddings, properties.ollama().model());
    }
    @Bean
    ChunkTokenEstimator ragChunkTokenEstimator() {
        var encoding = com.knuddels.jtokkit.Encodings.newDefaultEncodingRegistry()
                .getEncoding(com.knuddels.jtokkit.api.EncodingType.CL100K_BASE);
        return encoding::countTokensOrdinary;
    }

    @Bean
    DocumentChunker ragDocumentChunker(ChunkTokenEstimator tokens, com.github.vladsaraykin.aichat.rag.domain.ChunkingSettings settings) {
        return new DocumentChunker(tokens, settings);
    }

    @Bean
    ChunkingService ragChunkingService(DocumentService documents, DocumentChunker chunker,
                                       ChunkTokenEstimator tokens, com.github.vladsaraykin.aichat.rag.domain.ChunkingSettings settings) {
        return new ChunkingService(documents, chunker, tokens, settings);
    }
    @Bean
    DocumentStore ragDocumentStore(RagProperties properties,
                                   @Value("${app.rag.documents-directory:data/rag-documents}") String directory) throws IOException {
        return new LocalDocumentStore(Path.of(directory));
    }

    @Bean
    DocumentExtractor ragDocumentExtractor() { return new OfficeDocumentExtractor(); }

    @Bean
    DocumentRepository ragDocumentRepository(@Qualifier("ragJdbcTemplate") JdbcTemplate jdbc) {
        return new JdbcDocumentRepository(jdbc);
    }

    @Bean
    DocumentService ragDocumentService(DocumentRepository repository, DocumentStore store, DocumentExtractor extractor) {
        return new DocumentService(repository, store, extractor);
    }

    @Bean(destroyMethod = "close")
    HikariDataSource ragDataSource(RagProperties properties) {
        var database = properties.database();
        var config = new HikariConfig();
        config.setPoolName("rag-pool");
        config.setJdbcUrl(database.url());
        config.setUsername(database.username());
        config.setPassword(database.password());
        config.setMaximumPoolSize(database.maximumPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(database.connectionTimeout().toMillis());
        config.setDataSourceProperties(driverProperties(database));
        return new HikariDataSource(config);
    }

    private static java.util.Properties driverProperties(RagProperties.Database database) {
        var properties = new java.util.Properties();
        properties.setProperty("connectTimeout", Long.toString(Math.max(1, database.connectionTimeout().toSeconds())));
        properties.setProperty("socketTimeout", "30");
        properties.setProperty("ApplicationName", "ai-advent-rag");
        return properties;
    }

    @Bean(initMethod = "migrate")
    Flyway ragFlyway(@Qualifier("ragDataSource") DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/rag/migration")
                .defaultSchema("rag").schemas("rag")
                .cleanDisabled(true).baselineOnMigrate(false).load();
    }

    @Bean
    @DependsOn("ragFlyway")
    JdbcTemplate ragJdbcTemplate(@Qualifier("ragDataSource") DataSource dataSource) {
        var template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(30);
        return template;
    }

    @Bean
    @DependsOn("ragFlyway")
    TransactionTemplate ragTransactions(@Qualifier("ragDataSource") DataSource dataSource) {
        var template = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        template.setTimeout(30);
        return template;
    }
}
