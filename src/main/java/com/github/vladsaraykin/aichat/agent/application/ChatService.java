package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final AgentCatalog registry;
    private final ChatRepository repository;
    private final Semaphore calls = new Semaphore(4);
    private final Set<UUID> busyChats = ConcurrentHashMap.newKeySet();

    public ChatService(AgentCatalog registry, ChatRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }
    public List<AgentDefinition> agents() { return registry.definitions(); }
    public List<Chat> list(String agentId) {
        registry.get(agentId);
        return repository.list(agentId);
    }
    public Chat create(String agentId) {
        registry.get(agentId);
        Chat chat = Chat.create(agentId);
        repository.save(chat);
        log.info("chat_created agentId={} chatId={}", agentId, chat.id());
        return chat;
    }
    public Chat get(String agentId, UUID chatId) {
        registry.get(agentId);
        return repository.get(agentId, chatId);
    }
    public Chat send(String agentId, UUID chatId, UUID messageId, String content) {
        Agent agent = registry.get(agentId);
        if (!busyChats.add(chatId)) throw new ChatFailure(ChatFailure.Kind.BUSY, "В этом чате уже ожидается ответ. Повторите позже.");
        boolean acquired = false;
        try {
            Chat chat = repository.get(agentId, chatId);
            if (duplicate(chat, messageId, content)) return chat;
            if (!(acquired = calls.tryAcquire())) {
                throw new ChatFailure(ChatFailure.Kind.BUSY, "Агенты заняты. Повторите отправку через несколько секунд.");
            }
            MDC.put("requestId", messageId.toString());
            MDC.put("chatId", chatId.toString());
            log.info("agent_request agentId={} chatId={} requestId={} historyMessages={} promptLength={}",
                    agentId, chatId, messageId, chat.messages().size(), content.length());
            var user = new ChatMessage(messageId, ChatMessage.Role.USER, content, Instant.now(), null);
            boolean compressionFailed = false;
            Chat prepared = chat;
            if (shouldCompress(agent, chat)) {
                try {
                    prepared = compress(agent, chat).block();
                } catch (ChatFailure failure) {
                    compressionFailed = true;
                    log.warn("context_summary_failed phase=before_answer agentId={} chatId={} requestId={} kind={}",
                            agentId, chatId, messageId, failure.kind());
                }
            }
            ChatMessage answer = agent.answer(prepared.summary(), prepared.messages(), user);
            Chat appended = prepared.append(user, answer);
            Chat updated = appended;
            if (!compressionFailed && shouldCompress(agent, appended)) {
                try {
                    updated = compress(agent, appended).block();
                } catch (ChatFailure failure) {
                    log.warn("context_summary_failed phase=after_answer agentId={} chatId={} requestId={} kind={}",
                            agentId, chatId, messageId, failure.kind());
                }
            }
            repository.save(updated);
            log.info("agent_response agentId={} chatId={} requestId={} totalTokens={} costUsd={} "
                            + "summaryMessages={} summaryCalls={}",
                    agentId, chatId, messageId, answer.metrics().totalTokens(), answer.metrics().totalCostUsd(),
                    updated.summary() == null ? 0 : updated.summary().summarizedMessages(),
                    updated.summary() == null ? 0 : updated.summary().calls());
            return updated;
        } catch (ChatFailure exception) {
            log.warn("agent_request_failed agentId={} chatId={} requestId={} kind={}",
                    agentId, chatId, messageId, exception.kind(), exception);
            throw exception;
        } finally {
            MDC.remove("requestId");
            MDC.remove("chatId");
            if (acquired) calls.release();
            busyChats.remove(chatId);
        }
    }

    public Flux<StreamEvent> stream(String agentId, UUID chatId, UUID messageId, String content) {
        Agent agent = registry.get(agentId);
        if (!busyChats.add(chatId)) {
            throw new ChatFailure(ChatFailure.Kind.BUSY, "В этом чате уже ожидается ответ. Повторите позже.");
        }
        boolean acquired = calls.tryAcquire();
        if (!acquired) {
            busyChats.remove(chatId);
            throw new ChatFailure(ChatFailure.Kind.BUSY, "Агенты заняты. Повторите отправку через несколько секунд.");
        }
        try {
            Chat chat = repository.get(agentId, chatId);
            if (duplicate(chat, messageId, content)) {
                releaseStream(chatId);
                return Flux.just(StreamEvent.completed(chat));
            }
            log.info("agent_stream_request agentId={} chatId={} requestId={} historyMessages={} promptLength={}",
                    agentId, chatId, messageId, chat.messages().size(), content.length());
            var user = new ChatMessage(messageId, ChatMessage.Role.USER, content, Instant.now(), null);
            Flux<StreamEvent> response = prepare(agent, chat, agentId, chatId, messageId)
                    .flatMapMany(prepared -> answer(agent, prepared, user, agentId, chatId, messageId));
            Flux<StreamEvent> execution = shouldCompress(agent, chat)
                    ? Flux.concat(Flux.just(StreamEvent.summarizing()), response) : response;
            return Flux.concat(Flux.just(StreamEvent.started(messageId)), execution)
                    .doOnError(exception -> log.warn(
                            "agent_stream_failed agentId={} chatId={} requestId={} errorType={}",
                            agentId, chatId, messageId, exception.getClass().getSimpleName(), exception))
                    .doFinally(signal -> releaseStream(chatId));
        } catch (RuntimeException exception) {
            releaseStream(chatId);
            throw exception;
        }
    }

    private Mono<PreparedChat> prepare(Agent agent, Chat chat, String agentId, UUID chatId, UUID messageId) {
        if (!shouldCompress(agent, chat)) return Mono.just(new PreparedChat(chat, null, false));
        return compress(agent, chat)
                .map(compacted -> new PreparedChat(compacted, null, false))
                .onErrorResume(ChatFailure.class, failure -> {
                    log.warn("context_summary_failed phase=before_answer agentId={} chatId={} requestId={} kind={}",
                            agentId, chatId, messageId, failure.kind());
                    return Mono.just(new PreparedChat(chat,
                            "Не удалось сжать старую историю; ответ построен по полному доступному контексту.", true));
                });
    }

    private Flux<StreamEvent> answer(Agent agent, PreparedChat prepared, ChatMessage user,
                                     String agentId, UUID chatId, UUID messageId) {
        Flux<StreamEvent> response = agent.answerStream(prepared.chat().summary(), prepared.chat().messages(), user)
                .concatMap(part -> {
                    if (part.completed() == null) return Mono.just(StreamEvent.delta(part.delta()));
                    Chat appended = prepared.chat().append(user, part.completed());
                    boolean needsCompression = !prepared.compressionFailed() && shouldCompress(agent, appended);
                    Mono<PreparedChat> finalized = needsCompression
                            ? compress(agent, appended).map(chat -> new PreparedChat(chat, prepared.warning(), false))
                                .onErrorResume(ChatFailure.class, failure -> {
                                    log.warn("context_summary_failed phase=after_answer agentId={} chatId={} "
                                                    + "requestId={} kind={}",
                                            agentId, chatId, messageId, failure.kind());
                                    return Mono.just(new PreparedChat(appended, joinWarnings(prepared.warning(),
                                            "Ответ сохранён, но сжатие истории не выполнено."), true));
                                })
                            : Mono.just(new PreparedChat(appended, prepared.warning(), prepared.compressionFailed()));
                    Mono<StreamEvent> completed = finalized.map(result -> {
                        repository.save(result.chat());
                        log.info("agent_stream_response agentId={} chatId={} requestId={} totalTokens={} costUsd={} "
                                        + "summaryMessages={} summaryCalls={}",
                                agentId, chatId, messageId, part.completed().metrics().totalTokens(),
                                part.completed().metrics().totalCostUsd(),
                                result.chat().summary() == null ? 0 : result.chat().summary().summarizedMessages(),
                                result.chat().summary() == null ? 0 : result.chat().summary().calls());
                        return StreamEvent.completed(result.chat(), result.warning());
                    });
                    return needsCompression
                            ? Flux.concat(Flux.just(StreamEvent.summarizing()), completed) : completed;
                });
        return response;
    }

    private Mono<Chat> compress(Agent agent, Chat chat) {
        int retained = agent.definition().compression().recentMessages();
        int removed = chat.messages().size() - retained;
        List<ChatMessage> batch = List.copyOf(chat.messages().subList(0, removed));
        log.info("context_summary_started agentId={} chatId={} batchMessages={} previousSummaryMessages={}",
                chat.agentId(), chat.id(), batch.size(),
                chat.summary() == null ? 0 : chat.summary().summarizedMessages());
        return agent.summarize(chat.summary(), batch).map(summary -> {
            Chat compacted = chat.compact(summary, removed);
            log.info("context_summary_completed agentId={} chatId={} summarizedMessages={} retainedMessages={} "
                            + "summaryTokens={} summaryCostUsd={}",
                    chat.agentId(), chat.id(), summary.summarizedMessages(), compacted.messages().size(),
                    summary.totalTokens(), summary.totalCostUsd());
            return compacted;
        });
    }

    private boolean shouldCompress(Agent agent, Chat chat) {
        var compression = agent.definition().compression();
        return compression.enabled()
                && chat.messages().size() - compression.recentMessages() >= compression.batchSize();
    }

    private static String joinWarnings(String first, String second) {
        return first == null ? second : first + " " + second;
    }

    private static boolean duplicate(Chat chat, UUID messageId, String content) {
        return switch (chat.matchUserMessage(messageId, content)) {
            case NONE -> false;
            case SAME -> true;
            case CONFLICT -> throw new ChatFailure(ChatFailure.Kind.INVALID,
                    "Идентификатор сообщения уже использован");
        };
    }

    private void releaseStream(UUID chatId) {
        calls.release();
        busyChats.remove(chatId);
    }

    private record PreparedChat(Chat chat, String warning, boolean compressionFailed) { }

    public record StreamEvent(Type type, UUID messageId, String text, Chat chat, String warning) {
        public enum Type { STARTED, SUMMARIZING, DELTA, COMPLETED }
        static StreamEvent started(UUID id) { return new StreamEvent(Type.STARTED, id, null, null, null); }
        static StreamEvent summarizing() { return new StreamEvent(Type.SUMMARIZING, null, null, null, null); }
        static StreamEvent delta(String text) { return new StreamEvent(Type.DELTA, null, text, null, null); }
        static StreamEvent completed(Chat chat) { return completed(chat, null); }
        static StreamEvent completed(Chat chat, String warning) {
            return new StreamEvent(Type.COMPLETED, null, null, chat, warning);
        }
    }
}
