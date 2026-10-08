package com.github.vladsaraykin.aichat.rag.infrastructure;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** PostgreSQL is required for the harness even when chat retrieval is disabled. Never log credentials. */
@ConfigurationProperties("app.rag")
public record RagProperties(Database database, Ollama ollama) {
    public RagProperties {
        if (database == null || ollama == null) {
            throw new IllegalArgumentException("RAG database and Ollama configuration are required");
        }
    }

    public record Database(String url, String username, String password,
                           int maximumPoolSize, Duration connectionTimeout) {
        public Database {
            if (url == null || !url.startsWith("jdbc:postgresql://")
                    || url.contains("?") || url.contains("#") || url.contains("@")
                    || username == null || username.isBlank() || password == null || password.isBlank()) {
                throw new IllegalArgumentException("RAG requires a PostgreSQL URL without credentials/query parameters, username and password");
            }
            if (maximumPoolSize < 1 || maximumPoolSize > 50) {
                throw new IllegalArgumentException("RAG database pool size must be between 1 and 50");
            }
            requireTimeout(connectionTimeout, "database connection", Duration.ofMillis(250));
        }

        @Override public String toString() {
            return "Database[credentials=REDACTED, maximumPoolSize=" + maximumPoolSize + "]";
        }
    }

    public record Ollama(URI baseUrl, String model, int dimensions,
                         Duration connectTimeout, Duration requestTimeout) {
        public Ollama {
            if (baseUrl == null || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                    || baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                    || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
                throw new IllegalArgumentException("RAG Ollama URL must be HTTP(S) without credentials, query or fragment");
            }
            if (model == null || model.isBlank() || dimensions != 768) {
                throw new IllegalArgumentException("RAG requires an embedding model with 768 dimensions; changing dimensions requires a migration");
            }
            requireTimeout(connectTimeout, "Ollama connection", Duration.ofMillis(1));
            requireTimeout(requestTimeout, "Ollama request", Duration.ofMillis(1));
        }
    }

    private static void requireTimeout(Duration value, String label, Duration minimum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid RAG " + label + " timeout");
        }
    }
}
