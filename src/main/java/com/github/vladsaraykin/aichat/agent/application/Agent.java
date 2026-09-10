package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.AgentDefinition;
import com.github.vladsaraykin.aichat.agent.domain.ChatMessage;
import com.github.vladsaraykin.aichat.agent.domain.ContextSummary;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface Agent {
    AgentDefinition definition();
    ChatMessage answer(List<ChatMessage> history, ChatMessage userMessage);
    default ChatMessage answer(ContextSummary summary, List<ChatMessage> history, ChatMessage userMessage) {
        return answer(history, userMessage);
    }
    Flux<AnswerPart> answerStream(List<ChatMessage> history, ChatMessage userMessage);
    default Flux<AnswerPart> answerStream(ContextSummary summary, List<ChatMessage> history,
                                          ChatMessage userMessage) {
        return answerStream(history, userMessage);
    }
    Mono<ContextSummary> summarize(ContextSummary previous, List<ChatMessage> messages);

    record AnswerPart(String delta, ChatMessage completed) {
        public static AnswerPart delta(String text) { return new AnswerPart(text, null); }
        public static AnswerPart completed(ChatMessage message) { return new AnswerPart(null, message); }
    }
}
