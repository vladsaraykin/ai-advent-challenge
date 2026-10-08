package com.github.vladsaraykin.aichat.agent.infrastructure;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.harness.domain.ChatSettings.LlmProvider;
import com.github.vladsaraykin.aichat.harness.domain.RequestContext;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@Primary
@EnableConfigurationProperties(LocalLlmProperties.class)
public class RoutingConversationModel implements ConversationModel {
    private final ConversationModel cloud;
    private final ConversationModel local;
    private final LocalLlmProperties settings;
    private final boolean cloudEnabled;
    public RoutingConversationModel(@Qualifier("openAiConversationModel") ConversationModel cloud,
            LocalMlxConversationModel local, LocalLlmProperties settings,
            @Value("${spring.ai.openai.api-key:missing-api-key}") String apiKey) {
        this.cloud=cloud; this.local=local; this.settings=settings;
        cloudEnabled=apiKey!=null && !apiKey.isBlank() && !apiKey.equals("missing-api-key");
    }
    public boolean cloudEnabled() { return cloudEnabled; }
    @Override public void validateSettings(com.github.vladsaraykin.aichat.harness.domain.ChatSettings chat) {
        if(chat.provider()==LlmProvider.LOCAL_MLX && !settings.toolsEnabled() && !chat.mcpServerIds().isEmpty())
            throw new ChatFailure(ChatFailure.Kind.INVALID,"MCP для локальной модели отключён. Уберите выбранные MCP-серверы в настройках чата.");
    }
    @Override public AgentDefinition configuredDefinition(AgentDefinition d, LlmProvider provider) {
        if(provider!=LlmProvider.LOCAL_MLX) {
            if(!cloudEnabled) throw new ChatFailure(ChatFailure.Kind.INVALID,
                    "OpenAI не настроен. Выберите Local MLX в настройках чата.");
            return d;
        }
        if(!settings.enabled()) throw new ChatFailure(ChatFailure.Kind.INVALID,"Локальная модель отключена на сервере.");
        return new AgentDefinition(d.id(),d.name(),d.description(),settings.model(),d.systemPrompt(),
                Math.min(d.maxCompletionTokens(),settings.maxCompletionTokens()),null,settings.timeoutSeconds(),
                d.maxHistoryChars(),new TokenPricing(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO),
                d.compression(),d.contextManagement(),d.memoryLayers(),d.invariants(),d.lifecycle(),LlmProvider.LOCAL_MLX);
    }
    private ConversationModel selected(AgentDefinition d) {
        if(d.provider()==LlmProvider.LOCAL_MLX) return local;
        if(!cloudEnabled) throw new ChatFailure(ChatFailure.Kind.INVALID,"OpenAI не настроен. Выберите Local MLX.");
        return cloud;
    }
    @Override public Reply reply(AgentDefinition d,List<ChatMessage> m) { return selected(d).reply(d,m); }
    @Override public Reply reply(AgentDefinition d,ContextSummary s,List<ChatMessage> m) { return selected(d).reply(d,s,m); }
    @Override public Flux<StreamPart> stream(AgentDefinition d,List<ChatMessage> m) { return selected(d).stream(d,m); }
    @Override public Flux<StreamPart> stream(AgentDefinition d,ContextSummary s,List<ChatMessage> m) { return selected(d).stream(d,s,m); }
    @Override public Flux<StreamPart> stream(AgentDefinition d,ContextSummary s,List<ChatMessage> m,List<String> servers) { return selected(d).stream(d,s,m,servers); }
    @Override public Flux<StreamPart> stream(AgentDefinition d,ContextSummary s,List<ChatMessage> m,List<String> servers,RequestContext c) { return selected(d).stream(d,s,m,servers,c); }
    @Override public Mono<Reply> summarize(AgentDefinition d,ContextSummary s,List<ChatMessage> m) { return selected(d).summarize(d,s,m); }
    @Override public Flux<StreamPart> extractFacts(AgentDefinition d,List<ChatMessage> m) { return selected(d).extractFacts(d,m); }
    @Override public Flux<StreamPart> extractMemory(AgentDefinition d,List<ChatMessage> m) { return selected(d).extractMemory(d,m); }
    @Override public Flux<StreamPart> extractQuestions(AgentDefinition d,List<ChatMessage> m) { return selected(d).extractQuestions(d,m); }
    @Override public Flux<StreamPart> checkInvariants(AgentDefinition d,List<ChatMessage> m) { return selected(d).checkInvariants(d,m); }
    @Override public Flux<StreamPart> checkLifecycle(AgentDefinition d,List<ChatMessage> m) { return selected(d).checkLifecycle(d,m); }
    @Override public void recordUsage(RequestContext c,ChatMessage.Metrics m) {
        if(c!=null) (c.settings().provider()==LlmProvider.LOCAL_MLX ? local : cloud).recordUsage(c,m);
    }
    @Override public List<com.github.vladsaraykin.aichat.rag.domain.RagQuestion.Source> toolSources(RequestContext c) {
        return c==null ? List.of() : (c.settings().provider()==LlmProvider.LOCAL_MLX ? local : cloud).toolSources(c);
    }
}
