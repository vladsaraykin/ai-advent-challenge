package com.github.vladsaraykin.aichat.review;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.rag.application.*;
import com.github.vladsaraykin.aichat.rag.infrastructure.*;
import com.github.vladsaraykin.aichat.review.application.*;
import com.github.vladsaraykin.aichat.review.infrastructure.ReviewConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewWiringTest {
    @Test void wiresReviewServiceAndRecoversInterruptedRequestsWithoutExternalCalls() {
        var jdbc = mock(JdbcTemplate.class);
        new ApplicationContextRunner().withUserConfiguration(ReviewConfiguration.class)
                .withBean("ragJdbcTemplate", JdbcTemplate.class, () -> jdbc)
                .withBean(ConversationModel.class, () -> mock(ConversationModel.class))
                .withBean(ProjectDocumentSyncRepository.class, () -> mock(ProjectDocumentSyncRepository.class))
                .withBean(RagQuestionRepository.class, () -> mock(RagQuestionRepository.class))
                .withBean(EmbeddingModel.class, () -> mock(EmbeddingModel.class))
                .withBean(RagProperties.class, () -> new RagProperties(
                        new RagProperties.Database("jdbc:postgresql://localhost/test", "test", "test", 1, java.time.Duration.ofSeconds(1)),
                        new RagProperties.Ollama(java.net.URI.create("http://localhost:11434"), "embeddinggemma", 768,
                                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1))))
                .withBean(Reranker.class, () -> mock(Reranker.class))
                .withBean(RerankerSettings.class, () -> new RerankerSettings(java.net.URI.create("http://localhost:8000"), 10, 20, 5, .2))
                .withBean(RagSourceSelector.class, () -> new RagSourceSelector(String::length, 6000))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(CodeReviewService.class).hasSingleBean(ReviewRepository.class);
                    verify(jdbc).update(contains("status='INTERRUPTED'"));
                });
    }
    @Test void disablingRagAlsoDisablesReviewDependencies() {
        new ApplicationContextRunner().withUserConfiguration(ReviewConfiguration.class).withPropertyValues("app.rag.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CodeReviewService.class));
    }
}
