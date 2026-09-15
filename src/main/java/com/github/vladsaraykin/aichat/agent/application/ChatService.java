package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final AgentCatalog registry;
    private final ChatRepository repository;
    private final LongTermMemoryRepository longTerm;
    private final Semaphore calls = new Semaphore(4);
    private final Set<UUID> busyChats = ConcurrentHashMap.newKeySet();
    public ChatService(AgentCatalog registry, ChatRepository repository) {
        this(registry, repository, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ChatService(AgentCatalog registry, ChatRepository repository, LongTermMemoryRepository longTerm) {
        this.registry = registry;
        this.repository = repository;
        this.longTerm = longTerm;
    }
    public List<AgentDefinition> agents() { return registry.definitions(); }
    public List<Chat> list(String agentId) { registry.get(agentId); return repository.list(agentId); }
    public Chat get(String agentId, UUID chatId) { registry.get(agentId); return repository.get(agentId, chatId); }
    private void requireLayers(String agentId) {
        if (!registry.get(agentId).definition().memoryLayers().enabled())
            throw new ChatFailure(ChatFailure.Kind.INVALID, "Слои памяти не включены у этого агента.");
        if (longTerm == null) throw new ChatFailure(ChatFailure.Kind.STORAGE, "Хранилище памяти не настроено.");
    }
    public LongTermMemory memory(String agentId) { requireLayers(agentId); return longTerm.get(agentId); }
    public Chat editTask(String agentId, UUID chatId, long version, String projectKey, MemoryService.TaskData data) {
        return mutateTask(agentId, chatId, version, old -> MemoryService.update(old, data, projectKey, old.proposals(), null));
    }
    public Chat advanceTask(String agentId, UUID chatId, long version) {
        return mutateTask(agentId, chatId, version, MemoryService::advance);
    }
    public Chat rejectProposal(String agentId, UUID chatId, long version, UUID proposalId) {
        return mutateTask(agentId, chatId, version, old -> MemoryService.reject(old, proposalId));
    }
    private Chat mutateTask(String agentId, UUID chatId, long version, java.util.function.UnaryOperator<WorkingMemory> update) {
        requireLayers(agentId); lock(chatId);
        try {
            Chat chat = repository.get(agentId, chatId);
            if (chat.readOnly()) throw new ChatFailure(ChatFailure.Kind.INVALID, "Checkpoint доступен только для чтения.");
            MemoryService.checkVersion(chat.workingMemory().version(), version);
            Chat updated;
            try { updated = chat.withWorkingMemory(update.apply(chat.workingMemory())); }
            catch (IllegalArgumentException e) { throw new ChatFailure(ChatFailure.Kind.INVALID, "Проверьте поля памяти и идентификатор проекта."); }
            repository.save(updated);
            log.info("task_memory_updated agentId={} chatId={} version={} stage={}", agentId, chatId,
                    updated.workingMemory().version(), updated.workingMemory().stage());
            return updated;
        } finally { busyChats.remove(chatId); }
    }
    public LongTermMemory acceptProposal(String agentId, UUID chatId, long version, long taskVersion, UUID proposalId) {
        requireLayers(agentId); lock(chatId);
        try {
            Chat chat = repository.get(agentId, chatId);
            if (chat.readOnly()) throw new ChatFailure(ChatFailure.Kind.INVALID, "Сохранение из checkpoint недоступно.");
            var proposal = chat.workingMemory().proposals().stream().filter(p -> p.id().equals(proposalId)).findFirst()
                    .orElseThrow(() -> new ChatFailure(ChatFailure.Kind.NOT_FOUND, "Предложение не найдено"));
            var existing = longTerm.get(agentId);
            if (existing.resolvedProposals().contains(proposalId)) return existing;
            MemoryService.checkVersion(chat.workingMemory().version(), taskVersion);
            var entry = entry(proposal.id(), proposal.scope(), chat.workingMemory().projectKey(), proposal.key(), proposal.value(),
                    chatId, proposal.sourceMessageId());
            var result = longTerm.put(agentId, version, entry);
            log.info("long_term_confirmed agentId={} chatId={} entryId={} version={}", agentId, chatId, proposalId, result.version());
            return result;
        } finally { busyChats.remove(chatId); }
    }
    public LongTermMemory putMemory(String agentId, long version, UUID id, WorkingMemory.Scope scope,
                                   String projectKey, String key, String value) {
        requireLayers(agentId);
        var old = longTerm.get(agentId).entries().stream().filter(e -> e.id().equals(id)).findFirst();
        var result = longTerm.put(agentId, version, entry(id, scope, projectKey, key, value,
                old.map(LongTermMemory.Entry::sourceChatId).orElse(null), old.map(LongTermMemory.Entry::sourceMessageId).orElse(null)));
        log.info("long_term_updated agentId={} entryId={} version={}", agentId, id, result.version());
        return result;
    }
    private LongTermMemory.Entry entry(UUID id, WorkingMemory.Scope scope, String projectKey, String key, String value,
                                       UUID sourceChat, UUID sourceMessage) {
        try { return new LongTermMemory.Entry(id, scope, projectKey, key, value, sourceChat, sourceMessage, Instant.now()); }
        catch (IllegalArgumentException | NullPointerException e) {
            throw new ChatFailure(ChatFailure.Kind.INVALID, "Проверьте запись памяти. Для области PROJECT укажите идентификатор проекта в задаче.");
        }
    }
    public LongTermMemory deleteMemory(String agentId, long version, UUID entryId) {
        requireLayers(agentId);
        var result = longTerm.delete(agentId, version, entryId);
        log.info("long_term_deleted agentId={} entryId={} version={}", agentId, entryId, result.version());
        return result;
    }
    public void delete(String agentId, UUID chatId) {
        registry.get(agentId);
        List<UUID> ids;
        synchronized (busyChats) {
            ids = subtree(repository.get(agentId, chatId)).map(Chat::id).toList();
            if (ids.stream().anyMatch(busyChats::contains)) {
                throw new ChatFailure(ChatFailure.Kind.BUSY,
                        "Дождитесь завершения запроса в чате или его ветках перед удалением.");
            }
            busyChats.addAll(ids);
        }
        try {
            repository.delete(agentId, chatId);
            log.info("chat_deleted agentId={} chatId={} deletedChats={}", agentId, chatId, ids.size());
        } finally {
            synchronized (busyChats) { busyChats.removeAll(ids); }
        }
    }
    private java.util.stream.Stream<Chat> subtree(Chat chat) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(chat), chat.branches().stream().flatMap(this::subtree));
    }
    public Chat create(String agentId) { return create(agentId, registry.get(agentId).definition().contextManagement().defaultStrategy()); }
    public Chat create(String agentId, ContextStrategyType strategy) {
        registry.get(agentId);
        Chat chat = Chat.create(agentId, strategy);
        repository.save(chat);
        log.info("chat_created agentId={} chatId={} strategy={}", agentId, chat.id(), strategy);
        return chat;
    }
    public Chat fork(String agentId, UUID chatId, String first, String second) {
        registry.get(agentId);
        lock(chatId);
        try {
            Chat chat = repository.get(agentId, chatId);
            if (chat.strategy() != ContextStrategyType.BRANCHING || chat.messages().isEmpty()) {
                throw new ChatFailure(ChatFailure.Kind.INVALID, "Развилка доступна после первого ответа в Branching-чате.");
            }
            if (chat.readOnly()) return chat;
            Chat forked = chat.fork(first, second);
            repository.save(forked);
            log.info("chat_branched agentId={} chatId={} checkpointId={}", agentId, chatId, forked.checkpointId());
            return forked;
        } finally { busyChats.remove(chatId); }
    }
    public Chat send(String agentId, UUID chatId, UUID messageId, String content) {
        return stream(agentId, chatId, messageId, content).filter(e -> e.type() == StreamEvent.Type.COMPLETED)
                .single().block().chat();
    }
    public Flux<StreamEvent> stream(String agentId, UUID chatId, UUID messageId, String content) {
        Agent agent = registry.get(agentId);
        lock(chatId);
        if (!calls.tryAcquire()) {
            busyChats.remove(chatId);
            throw new ChatFailure(ChatFailure.Kind.BUSY, "Агенты заняты. Повторите отправку через несколько секунд.");
        }
        try {
            Chat chat = repository.get(agentId, chatId);
            if (duplicate(chat, messageId, content)) {
                release(chatId);
                return Flux.just(StreamEvent.completed(chat, null));
            }
            if (chat.readOnly()) {
                throw new ChatFailure(ChatFailure.Kind.INVALID, "Этот диалог сохранён как checkpoint. Выберите ветку для продолжения.");
            }
            ContextStrategy strategy = ContextStrategy.forType(chat.strategy());
            boolean layered = agent.definition().memoryLayers().enabled();
            LongTermMemory durable = layered ? memory(agentId) : LongTermMemory.empty();
            List<LongTermMemory.Entry> entries = durable.relevant(chat.workingMemory().projectKey());
            var user = new ChatMessage(messageId, ChatMessage.Role.USER, content, Instant.now(), null);
            log.info("agent_stream_request agentId={} chatId={} requestId={} strategy={} historyMessages={} promptLength={}",
                    agentId, chatId, messageId, chat.strategy(), chat.messages().size(), content.length());
            Mono<Prepared> preparation = Mono.defer(() -> layered && chat.strategy() == ContextStrategyType.FACTS
                            ? Mono.just(chat) : strategy.before(agent, chat, user))
                    .map(value -> new Prepared(value, null, false))
                    .onErrorResume(ChatFailure.class, failure -> summaryFallback(chat, failure));
            if (layered) preparation = preparation.flatMap(prepared -> agent.prepareMemory(prepared.chat().withWorkingMemory(
                            prepared.chat().workingMemory().withoutResolved(durable.resolvedProposals())), user, entries)
                    .map(memory -> new Prepared(prepared.chat().withWorkingMemory(memory), prepared.warning(), prepared.memoryFailed())));
            Flux<StreamEvent> execution = preparation.flatMapMany(prepared ->
                    agent.answerStream(strategy.context(agent, prepared.chat()), user, entries).concatMap(part -> {
                        if (part.completed() == null) return Mono.just(StreamEvent.delta(part.delta()));
                        Mono<Chat> appended = Mono.defer(() -> agent.completeMemory(prepared.chat(), user, part.completed()))
                                .map(memory -> prepared.chat().withWorkingMemory(memory).append(user, part.completed()));
                        Flux<StreamEvent> completion = appended.flatMapMany(value -> {
                            Mono<Prepared> finalized = prepared.memoryFailed()
                                    ? Mono.just(new Prepared(value, prepared.warning(), true))
                                    : Mono.defer(() -> strategy.after(agent, value))
                                        .map(result -> new Prepared(result, prepared.warning(), false))
                                        .onErrorResume(ChatFailure.class, failure -> summaryFallback(value, failure));
                            Mono<StreamEvent> completed = finalized.map(result -> {
                                repository.save(result.chat());
                                log.info("agent_stream_response agentId={} chatId={} requestId={} strategy={} totalTokens={} costUsd={}",
                                        agentId, chatId, messageId, chat.strategy(), part.completed().metrics().totalTokens(),
                                        part.completed().metrics().totalCostUsd());
                                return StreamEvent.completed(result.chat(), result.warning());
                            });
                            return phase(prepared.memoryFailed() ? null : strategy.afterPhase(agent, value), completed.flux());
                        });
                        return phase(layered && !prepared.chat().workingMemory().goal().isBlank() ? "SYNCING_QUESTIONS" : null,
                                completion);
                    }));
            return Flux.concat(Flux.just(StreamEvent.started(messageId)),
                            phase(layered ? "UPDATING_MEMORY" : strategy.beforePhase(agent, chat), execution))
                    .doOnError(error -> log.warn("agent_stream_failed agentId={} chatId={} requestId={} errorType={}",
                            agentId, chatId, messageId, error.getClass().getSimpleName()))
                    .doFinally(signal -> release(chatId));
        } catch (RuntimeException error) { release(chatId); throw error; }
    }
    private Mono<Prepared> summaryFallback(Chat chat, ChatFailure failure) {
        if (chat.strategy() != ContextStrategyType.SUMMARY) return Mono.error(failure);
        log.warn("context_summary_failed agentId={} chatId={} kind={}", chat.agentId(), chat.id(), failure.kind());
        return Mono.just(new Prepared(chat, "Доступные сообщения сохранены; сжатие истории не выполнено.", true));
    }
    private Flux<StreamEvent> phase(String phase, Flux<StreamEvent> next) {
        return phase == null ? next : Flux.concat(Flux.just(new StreamEvent(StreamEvent.Type.valueOf(phase),
                null, null, null, null)), next);
    }
    private void lock(UUID id) {
        synchronized (busyChats) {
            if (!busyChats.add(id)) throw new ChatFailure(ChatFailure.Kind.BUSY, "В этом чате уже ожидается ответ. Повторите позже.");
        }
    }
    private void release(UUID id) { calls.release(); busyChats.remove(id); }
    private static boolean duplicate(Chat chat, UUID id, String content) {
        return switch (chat.matchUserMessage(id, content)) {
            case NONE -> false;
            case SAME -> true;
            case CONFLICT -> throw new ChatFailure(ChatFailure.Kind.INVALID, "Идентификатор сообщения уже использован");
        };
    }
    private record Prepared(Chat chat, String warning, boolean memoryFailed) { }
    public record StreamEvent(Type type, UUID messageId, String text, Chat chat, String warning) {
        public enum Type { STARTED, SUMMARIZING, UPDATING_FACTS, UPDATING_MEMORY, SYNCING_QUESTIONS, DELTA, COMPLETED }
        static StreamEvent started(UUID id) { return new StreamEvent(Type.STARTED, id, null, null, null); }
        static StreamEvent delta(String text) { return new StreamEvent(Type.DELTA, null, text, null, null); }
        static StreamEvent completed(Chat chat, String warning) { return new StreamEvent(Type.COMPLETED, null, null, chat, warning); }
    }
}
