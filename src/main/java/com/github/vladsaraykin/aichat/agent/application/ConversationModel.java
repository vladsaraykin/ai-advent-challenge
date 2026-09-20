package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.AgentDefinition;
import com.github.vladsaraykin.aichat.agent.domain.ChatMessage;
import com.github.vladsaraykin.aichat.agent.domain.ContextSummary;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ConversationModel {
    Reply reply(AgentDefinition definition, List<ChatMessage> messages);
    default Reply reply(AgentDefinition definition, ContextSummary summary, List<ChatMessage> messages) {
        return reply(definition, messages);
    }
    default Flux<StreamPart> stream(AgentDefinition definition, List<ChatMessage> messages) {
        return Flux.defer(() -> Flux.just(StreamPart.completed(reply(definition, messages))));
    }
    default Flux<StreamPart> stream(AgentDefinition definition, ContextSummary summary,
                                    List<ChatMessage> messages) {
        return stream(definition, messages);
    }
    default Mono<Reply> summarize(AgentDefinition definition, ContextSummary previous,
                                  List<ChatMessage> messages) {
        return Mono.fromSupplier(() -> reply(definition, messages));
    }
    default Flux<StreamPart> extractFacts(AgentDefinition definition, List<ChatMessage> messages) {
        return stream(definition, messages);
    }
    default Flux<StreamPart> extractMemory(AgentDefinition definition, List<ChatMessage> messages) {
        return stream(definition, messages);
    }
    default Flux<StreamPart> extractQuestions(AgentDefinition definition, List<ChatMessage> messages) {
        return stream(definition, messages);
    }
    default Flux<StreamPart> checkInvariants(AgentDefinition definition, List<ChatMessage> messages) {
        return stream(definition, messages);
    }
    default Flux<StreamPart> checkLifecycle(AgentDefinition definition, List<ChatMessage> messages) {
        return stream(definition, messages);
    }

    record Reply(String text, ChatMessage.Metrics metrics) { }
    record StreamPart(String delta, Reply completed) {
        public static StreamPart delta(String text) { return new StreamPart(text, null); }
        public static StreamPart completed(Reply reply) { return new StreamPart(null, reply); }
    }
}
