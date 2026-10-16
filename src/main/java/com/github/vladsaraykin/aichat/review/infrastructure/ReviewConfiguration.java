package com.github.vladsaraykin.aichat.review.infrastructure;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.rag.application.*;
import com.github.vladsaraykin.aichat.rag.infrastructure.*;
import com.github.vladsaraykin.aichat.review.application.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app", name = {"rag.enabled", "review.enabled"}, havingValue = "true", matchIfMissing = true)
public class ReviewConfiguration {
    @Bean("codeReviewDefinition") com.github.vladsaraykin.aichat.agent.domain.AgentDefinition codeReviewDefinition(
            ConversationModel model, @Value("${app.review.agent-location:classpath:review-agents/*.yaml}") String location) throws java.io.IOException {
        return new com.github.vladsaraykin.aichat.agent.infrastructure.AgentRegistry(model, location).get("code-reviewer").definition();
    }
    @Bean(initMethod = "recover") JdbcReviewRepository reviewRepository(@Qualifier("ragJdbcTemplate") JdbcTemplate jdbc) {
        return new JdbcReviewRepository(jdbc);
    }
    @Bean ReviewContextService reviewContextService(ProjectDocumentSyncRepository manifest, RagQuestionRepository repository,
            EmbeddingModel embeddings, RagProperties properties, Reranker reranker, RerankerSettings ranking, RagSourceSelector selector) {
        return new ReviewContextService(manifest, repository, embeddings, properties, reranker, ranking, selector);
    }
    @Bean CodeReviewService codeReviewService(@Qualifier("codeReviewDefinition") com.github.vladsaraykin.aichat.agent.domain.AgentDefinition definition,
            ConversationModel model, ReviewContextService context,
            ReviewRepository repository, @Value("${app.review.repository:vladsaraykin/ai-advent-challenge}") String allowedRepository) {
        return new CodeReviewService(definition, model, context, repository, allowedRepository);
    }
}
