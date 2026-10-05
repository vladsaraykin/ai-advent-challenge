package com.github.vladsaraykin.aichat.rag.infrastructure;

import com.github.vladsaraykin.aichat.rag.application.*;
import com.github.vladsaraykin.aichat.rag.domain.RagAnswerSettings;
import com.github.vladsaraykin.aichat.rag.domain.RetrievalOptions;
import com.github.vladsaraykin.aichat.user.application.UserRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="app.rag",name="enabled",havingValue="true")
@EnableConfigurationProperties({RagAnswerSettings.class,RerankerSettings.class})
public class RagQuestionConfiguration {
    @Bean RagQuestionRepository ragQuestionRepository(@Qualifier("ragJdbcTemplate") JdbcTemplate jdbc) { return new JdbcRagQuestionRepository(jdbc); }
    @Bean RagAnswerModel ragAnswerModel(ChatModel model, RagAnswerSettings settings) { return new OpenAiRagAnswerModel(model,settings); }
    @Bean(destroyMethod="close") HttpReranker ragReranker(RerankerSettings settings) { return new HttpReranker(settings); }
    @Bean RagSourceSelector ragSourceSelector(ChunkTokenEstimator tokens,RagAnswerSettings settings) {
        return new RagSourceSelector(tokens,settings.maxContextTokens());
    }
    @Bean(destroyMethod="close") RagQuestionService ragQuestionService(IndexRepository indexes, RagQuestionRepository repository,
            EmbeddingModel embeddings, RagProperties properties, RagAnswerModel model, RagAnswerSettings settings,
            ChunkTokenEstimator tokens, UserRepository users, HttpReranker reranker, RerankerSettings ranking) {
        return new RagQuestionService(indexes,repository,embeddings,properties.ollama().model(),model,settings,tokens,users,
                reranker,new RetrievalOptions(ranking.candidateK(),ranking.finalK(),ranking.threshold()));
    }
}
