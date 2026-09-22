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
    default Mono<com.github.vladsaraykin.aichat.agent.domain.WorkingMemory> completeMemory(
            com.github.vladsaraykin.aichat.agent.domain.Chat chat, ChatMessage user, ChatMessage assistant) {
        return Mono.just(chat.workingMemory());
    }
    default Mono<com.github.vladsaraykin.aichat.agent.domain.WorkingMemory> prepareMemory(
            com.github.vladsaraykin.aichat.agent.domain.Chat chat, ChatMessage user,
            List<com.github.vladsaraykin.aichat.agent.domain.LongTermMemory.Entry> entries) {
        return Mono.just(chat.workingMemory());
    }
    default Flux<AnswerPart> answerStream(com.github.vladsaraykin.aichat.agent.domain.Chat chat, ChatMessage user,
            List<com.github.vladsaraykin.aichat.agent.domain.LongTermMemory.Entry> entries) {
        return answerStream(chat, user);
    }
    default Flux<AnswerPart> answerStream(com.github.vladsaraykin.aichat.agent.domain.Chat chat, ChatMessage user,
            List<com.github.vladsaraykin.aichat.agent.domain.LongTermMemory.Entry> entries,
            com.github.vladsaraykin.aichat.user.domain.UserProfile profile) {
        return answerStream(chat, user, entries);
    }
    default Flux<AnswerPart> answerStream(com.github.vladsaraykin.aichat.agent.domain.Chat chat, ChatMessage user,
            List<com.github.vladsaraykin.aichat.agent.domain.LongTermMemory.Entry> entries,
            com.github.vladsaraykin.aichat.user.domain.UserProfile profile, String mcpServerId) {
        return answerStream(chat, user, entries, profile);
    }
    default Mono<com.github.vladsaraykin.aichat.agent.domain.ContextMemory> updateFacts(
            com.github.vladsaraykin.aichat.agent.domain.Chat chat, ChatMessage user) {
        return Mono.error(new UnsupportedOperationException("Facts are not supported"));
    }
    default Mono<InvariantCheck> checkInvariants(com.github.vladsaraykin.aichat.agent.domain.Chat chat,
            ChatMessage user, ChatMessage assistant) {
        return Mono.just(InvariantCheck.allowed(null));
    }
    default Mono<LifecycleCheck> checkLifecycle(com.github.vladsaraykin.aichat.agent.domain.Chat chat,
            ChatMessage user, ChatMessage assistant) {
        return Mono.just(LifecycleCheck.allowed(null));
    }
    default Flux<AnswerPart> answerStream(com.github.vladsaraykin.aichat.agent.domain.Chat chat, ChatMessage user) {
        return answerStream(chat.summary(), chat.messages(), user);
    }

    record AnswerPart(String delta, ChatMessage completed) {
        public static AnswerPart delta(String text) { return new AnswerPart(text, null); }
        public static AnswerPart completed(ChatMessage message) { return new AnswerPart(null, message); }
    }
    record InvariantConflict(java.util.UUID invariantId, String evidence, String explanation) { }
    record InvariantCheck(boolean allowed, List<InvariantConflict> conflicts, ChatMessage.Metrics metrics) {
        public InvariantCheck { conflicts = conflicts == null ? List.of() : List.copyOf(conflicts); }
        public static InvariantCheck allowed(ChatMessage.Metrics metrics) {
            return new InvariantCheck(true, List.of(), metrics);
        }
    }
    record LifecycleViolation(String code, String evidence, String explanation) { }
    record LifecycleCheck(boolean allowed, LifecycleViolation violation, ChatMessage.Metrics metrics) {
        public LifecycleCheck {
            if (allowed == (violation != null)) throw new IllegalArgumentException("Invalid lifecycle check");
        }
        public static LifecycleCheck allowed(ChatMessage.Metrics metrics) {
            return new LifecycleCheck(true, null, metrics);
        }
        public static LifecycleCheck blocked(String code, String evidence, String explanation,
                                             ChatMessage.Metrics metrics) {
            return new LifecycleCheck(false, new LifecycleViolation(code, evidence, explanation), metrics);
        }
    }
}
