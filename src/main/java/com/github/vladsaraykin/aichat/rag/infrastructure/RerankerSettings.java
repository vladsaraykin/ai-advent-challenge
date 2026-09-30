package com.github.vladsaraykin.aichat.rag.infrastructure;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.rag.reranker")
public record RerankerSettings(@DefaultValue("http://127.0.0.1:18090") URI baseUrl,
        @DefaultValue("60") int timeoutSeconds, @DefaultValue("20") int candidateK,
        @DefaultValue("5") int finalK, @DefaultValue("0.2") double threshold) {
    public RerankerSettings {
        if (baseUrl == null || !java.util.Set.of("http", "https").contains(baseUrl.getScheme())
                || baseUrl.getHost() == null || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null || timeoutSeconds < 5 || timeoutSeconds > 180) {
            throw new IllegalArgumentException("Invalid reranker configuration");
        }
        new com.github.vladsaraykin.aichat.rag.domain.RetrievalOptions(candidateK, finalK, threshold);
    }
}
