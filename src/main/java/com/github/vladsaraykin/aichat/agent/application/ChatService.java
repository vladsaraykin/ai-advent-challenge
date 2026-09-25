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
import com.github.vladsaraykin.aichat.user.application.UserRepository;
import com.github.vladsaraykin.aichat.user.domain.UserProfile;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final AgentCatalog registry;
    private final ChatRepository repository;
    private final LongTermMemoryRepository longTerm;
    private final UserRepository users;
    private final Semaphore calls = new Semaphore(4);
    private final Set<String> busyChats = ConcurrentHashMap.newKeySet();
    public ChatService(AgentCatalog registry, ChatRepository repository) {
        this(registry, repository, null, null);
    }
    public ChatService(AgentCatalog registry, ChatRepository repository, LongTermMemoryRepository longTerm) {
        this(registry, repository, longTerm, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ChatService(AgentCatalog registry, ChatRepository repository, LongTermMemoryRepository longTerm, UserRepository users) {
        this.registry = registry;
        this.repository = repository;
        this.longTerm = longTerm;
        this.users = users;
    }
    public List<AgentDefinition> agents() { return registry.definitions(); }
    public List<Chat> list(String agentId) { return list(ChatRepository.LEGACY_OWNER, agentId); }
    public List<Chat> list(String ownerId, String agentId) { registry.get(agentId); return repository.list(ownerId, agentId); }
    public Chat get(String agentId, UUID chatId) { return get(ChatRepository.LEGACY_OWNER, agentId, chatId); }
    public Chat get(String ownerId, String agentId, UUID chatId) { registry.get(agentId); return repository.get(ownerId, agentId, chatId); }
    private void requireLayers(String agentId) {
        if (!registry.get(agentId).definition().memoryLayers().enabled())
            throw new ChatFailure(ChatFailure.Kind.INVALID, "Слои памяти не включены у этого агента.");
        if (longTerm == null) throw new ChatFailure(ChatFailure.Kind.STORAGE, "Хранилище памяти не настроено.");
    }
    public LongTermMemory memory(String agentId) { return memory(ChatRepository.LEGACY_OWNER, agentId); }
    public LongTermMemory memory(String ownerId, String agentId) { requireLayers(agentId); return longTerm.get(ownerId, agentId); }
    public Chat editTask(String agentId, UUID chatId, long version, String projectKey, MemoryService.TaskData data) {
        return editTask(ChatRepository.LEGACY_OWNER, agentId, chatId, version, projectKey, data);
    }
    public Chat editTask(String ownerId, String agentId, UUID chatId, long version, String projectKey, MemoryService.TaskData data) {
        return mutateTask(ownerId, agentId, chatId, version, old -> MemoryService.update(old, data, projectKey, old.proposals(), null));
    }
    public Chat advanceTask(String agentId, UUID chatId, long version) {
        return advanceTask(ChatRepository.LEGACY_OWNER, agentId, chatId, version);
    }
    public Chat advanceTask(String ownerId, String agentId, UUID chatId, long version) {
        return mutateTask(ownerId, agentId, chatId, version, MemoryService::advance);
    }
    public Chat pauseTask(String ownerId, String agentId, UUID chatId, long version) {
        return mutateTask(ownerId, agentId, chatId, version, MemoryService::pause);
    }
    public Chat resumeTask(String ownerId, String agentId, UUID chatId, long version) {
        return mutateTask(ownerId, agentId, chatId, version, MemoryService::resume);
    }
    public Chat putInvariant(String ownerId, String agentId, UUID chatId, long version,
                             TaskInvariants.Entry entry) {
        requireInvariants(agentId); lock(ownerId, chatId);
        try {
            Chat chat = repository.get(ownerId, agentId, chatId);
            if (chat.readOnly()) throw new ChatFailure(ChatFailure.Kind.INVALID, "Checkpoint доступен только для чтения.");
            checkInvariantVersion(chat, version);
            Chat updated = chat.withInvariants(chat.invariants().put(entry));
            repository.save(ownerId, updated);
            log.info("task_invariant_saved agentId={} chatId={} invariantId={} type={} version={}",
                    agentId, chatId, entry.id(), entry.type(), updated.invariants().version());
            return updated;
        } catch (IllegalArgumentException error) {
            throw new ChatFailure(ChatFailure.Kind.INVALID, "Проверьте поля инварианта.");
        } finally { busyChats.remove(key(ownerId, chatId)); }
    }
    public Chat deleteInvariant(String ownerId, String agentId, UUID chatId, long version, UUID invariantId) {
        requireInvariants(agentId); lock(ownerId, chatId);
        try {
            Chat chat = repository.get(ownerId, agentId, chatId);
            if (chat.readOnly()) throw new ChatFailure(ChatFailure.Kind.INVALID, "Checkpoint доступен только для чтения.");
            checkInvariantVersion(chat, version);
            Chat updated = chat.withInvariants(chat.invariants().delete(invariantId));
            repository.save(ownerId, updated);
            log.info("task_invariant_deleted agentId={} chatId={} invariantId={} version={}",
                    agentId, chatId, invariantId, updated.invariants().version());
            return updated;
        } catch (IllegalArgumentException error) {
            throw new ChatFailure(ChatFailure.Kind.NOT_FOUND, "Инвариант не найден.");
        } finally { busyChats.remove(key(ownerId, chatId)); }
    }
    private void requireInvariants(String agentId) {
        if (!registry.get(agentId).definition().invariants().enabled()) {
            throw new ChatFailure(ChatFailure.Kind.INVALID, "Инварианты не включены у этого агента.");
        }
    }
    private static void checkInvariantVersion(Chat chat, long version) {
        if (chat.invariants().version() != version) {
            throw new ChatFailure(ChatFailure.Kind.BUSY, "Инварианты уже изменились. Обновите чат и повторите действие.");
        }
    }
    public Chat rejectProposal(String agentId, UUID chatId, long version, UUID proposalId) {
        return rejectProposal(ChatRepository.LEGACY_OWNER, agentId, chatId, version, proposalId);
    }
    public Chat rejectProposal(String ownerId, String agentId, UUID chatId, long version, UUID proposalId) {
        return mutateTask(ownerId, agentId, chatId, version, old -> MemoryService.reject(old, proposalId));
    }
    private Chat mutateTask(String ownerId, String agentId, UUID chatId, long version, java.util.function.UnaryOperator<WorkingMemory> update) {
        requireLayers(agentId); lock(ownerId, chatId);
        try {
            Chat chat = repository.get(ownerId, agentId, chatId);
            if (chat.readOnly()) throw new ChatFailure(ChatFailure.Kind.INVALID, "Checkpoint доступен только для чтения.");
            MemoryService.checkVersion(chat.workingMemory().version(), version);
            Chat updated;
            try { updated = chat.withWorkingMemory(update.apply(chat.workingMemory())); }
            catch (IllegalArgumentException e) { throw new ChatFailure(ChatFailure.Kind.INVALID, "Проверьте поля памяти и идентификатор проекта."); }
            repository.save(ownerId, updated);
            log.info("task_memory_updated agentId={} chatId={} version={} stage={} status={} expectedAction={}", agentId, chatId,
                    updated.workingMemory().version(), updated.workingMemory().stage(), updated.workingMemory().status(),
                    updated.workingMemory().expectedAction());
            return updated;
        } finally { busyChats.remove(key(ownerId, chatId)); }
    }
    public LongTermMemory acceptProposal(String agentId, UUID chatId, long version, long taskVersion, UUID proposalId) {
        return acceptProposal(ChatRepository.LEGACY_OWNER, agentId, chatId, version, taskVersion, proposalId);
    }
    public LongTermMemory acceptProposal(String ownerId, String agentId, UUID chatId, long version, long taskVersion, UUID proposalId) {
        requireLayers(agentId); lock(ownerId, chatId);
        try {
            Chat chat = repository.get(ownerId, agentId, chatId);
            if (chat.readOnly()) throw new ChatFailure(ChatFailure.Kind.INVALID, "Сохранение из checkpoint недоступно.");
            var proposal = chat.workingMemory().proposals().stream().filter(p -> p.id().equals(proposalId)).findFirst()
                    .orElseThrow(() -> new ChatFailure(ChatFailure.Kind.NOT_FOUND, "Предложение не найдено"));
            var existing = longTerm.get(ownerId, agentId);
            if (existing.resolvedProposals().contains(proposalId)) return existing;
            MemoryService.checkVersion(chat.workingMemory().version(), taskVersion);
            var entry = entry(proposal.id(), proposal.scope(), chat.workingMemory().projectKey(), proposal.key(), proposal.value(),
                    chatId, proposal.sourceMessageId());
            var result = longTerm.put(ownerId, agentId, version, entry);
            log.info("long_term_confirmed agentId={} chatId={} entryId={} version={}", agentId, chatId, proposalId, result.version());
            return result;
        } finally { busyChats.remove(key(ownerId, chatId)); }
    }
    public LongTermMemory putMemory(String agentId, long version, UUID id, WorkingMemory.Scope scope,
                                   String projectKey, String key, String value) {
        return putMemory(ChatRepository.LEGACY_OWNER, agentId, version, id, scope, projectKey, key, value);
    }
    public LongTermMemory putMemory(String ownerId, String agentId, long version, UUID id, WorkingMemory.Scope scope,
                                   String projectKey, String key, String value) {
        requireLayers(agentId);
        var old = longTerm.get(ownerId, agentId).entries().stream().filter(e -> e.id().equals(id)).findFirst();
        var result = longTerm.put(ownerId, agentId, version, entry(id, scope, projectKey, key, value,
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
        return deleteMemory(ChatRepository.LEGACY_OWNER, agentId, version, entryId);
    }
    public LongTermMemory deleteMemory(String ownerId, String agentId, long version, UUID entryId) {
        requireLayers(agentId);
        var result = longTerm.delete(ownerId, agentId, version, entryId);
        log.info("long_term_deleted agentId={} entryId={} version={}", agentId, entryId, result.version());
        return result;
    }
    public void delete(String agentId, UUID chatId) {
        delete(ChatRepository.LEGACY_OWNER, agentId, chatId);
    }
    public void delete(String ownerId, String agentId, UUID chatId) {
        registry.get(agentId);
        List<UUID> ids;
        synchronized (busyChats) {
            ids = subtree(repository.get(ownerId, agentId, chatId)).map(Chat::id).toList();
            if (ids.stream().map(id -> key(ownerId, id)).anyMatch(busyChats::contains)) {
                throw new ChatFailure(ChatFailure.Kind.BUSY,
                        "Дождитесь завершения запроса в чате или его ветках перед удалением.");
            }
            ids.stream().map(id -> key(ownerId, id)).forEach(busyChats::add);
        }
        try {
            repository.delete(ownerId, agentId, chatId);
            log.info("chat_deleted agentId={} chatId={} deletedChats={}", agentId, chatId, ids.size());
        } finally {
            synchronized (busyChats) { ids.stream().map(id -> key(ownerId, id)).forEach(busyChats::remove); }
        }
    }
    private java.util.stream.Stream<Chat> subtree(Chat chat) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(chat), chat.branches().stream().flatMap(this::subtree));
    }
    public Chat create(String agentId) { return create(ChatRepository.LEGACY_OWNER, agentId); }
    public Chat create(String ownerId, String agentId) { return create(ownerId, agentId, registry.get(agentId).definition().contextManagement().defaultStrategy()); }
    public Chat create(String agentId, ContextStrategyType strategy) {
        return create(ChatRepository.LEGACY_OWNER, agentId, strategy);
    }
    public Chat create(String ownerId, String agentId, ContextStrategyType strategy) {
        registry.get(agentId);
        Chat chat = Chat.create(agentId, strategy);
        repository.save(ownerId, chat);
        log.info("chat_created agentId={} chatId={} strategy={}", agentId, chat.id(), strategy);
        return chat;
    }
    public Chat fork(String agentId, UUID chatId, String first, String second) {
        return fork(ChatRepository.LEGACY_OWNER, agentId, chatId, first, second);
    }
    public Chat fork(String ownerId, String agentId, UUID chatId, String first, String second) {
        registry.get(agentId);
        lock(ownerId, chatId);
        try {
            Chat chat = repository.get(ownerId, agentId, chatId);
            if (chat.strategy() != ContextStrategyType.BRANCHING || chat.messages().isEmpty()) {
                throw new ChatFailure(ChatFailure.Kind.INVALID, "Развилка доступна после первого ответа в Branching-чате.");
            }
            if (chat.readOnly()) return chat;
            Chat forked = chat.fork(first, second);
            repository.save(ownerId, forked);
            log.info("chat_branched agentId={} chatId={} checkpointId={}", agentId, chatId, forked.checkpointId());
            return forked;
        } finally { busyChats.remove(key(ownerId, chatId)); }
    }
    public Chat send(String agentId, UUID chatId, UUID messageId, String content) {
        return send(ChatRepository.LEGACY_OWNER, agentId, chatId, messageId, content);
    }
    public Chat send(String ownerId, String agentId, UUID chatId, UUID messageId, String content) {
        return send(ownerId, agentId, chatId, messageId, content, null);
    }
    public Chat send(String ownerId, String agentId, UUID chatId, UUID messageId, String content,
                     List<String> mcpServerIds) {
        return stream(ownerId, agentId, chatId, messageId, content, mcpServerIds)
                .filter(e -> e.type() == StreamEvent.Type.COMPLETED)
                .single().block().chat();
    }
    public Flux<StreamEvent> stream(String agentId, UUID chatId, UUID messageId, String content) {
        return stream(ChatRepository.LEGACY_OWNER, agentId, chatId, messageId, content);
    }
    public Flux<StreamEvent> stream(String ownerId, String agentId, UUID chatId, UUID messageId, String content) {
        return stream(ownerId, agentId, chatId, messageId, content, null);
    }
    public Flux<StreamEvent> stream(String ownerId, String agentId, UUID chatId, UUID messageId, String content,
                                    List<String> mcpServerIds) {
        Agent agent = registry.get(agentId);
        lock(ownerId, chatId);
        if (!calls.tryAcquire()) {
            busyChats.remove(key(ownerId, chatId));
            throw new ChatFailure(ChatFailure.Kind.BUSY, "Агенты заняты. Повторите отправку через несколько секунд.");
        }
        try {
            Chat chat = repository.get(ownerId, agentId, chatId);
            if (duplicate(chat, messageId, content)) {
                release(ownerId, chatId);
                return Flux.just(StreamEvent.completed(chat, null));
            }
            if (chat.readOnly()) {
                throw new ChatFailure(ChatFailure.Kind.INVALID, "Этот диалог сохранён как checkpoint. Выберите ветку для продолжения.");
            }
            ContextStrategy strategy = ContextStrategy.forType(chat.strategy());
            boolean layered = agent.definition().memoryLayers().enabled();
            LongTermMemory durable = layered ? memory(ownerId, agentId) : LongTermMemory.empty();
            List<LongTermMemory.Entry> entries = durable.relevant(chat.workingMemory().projectKey());
            UserProfile profile = users == null ? UserProfile.initial(ownerId) : users.profile(ownerId);
            var user = new ChatMessage(messageId, ChatMessage.Role.USER, content, Instant.now(), null);
            log.info("agent_stream_request agentId={} chatId={} requestId={} strategy={} historyMessages={} "
                            + "promptLength={} mcpServers={}",
                    agentId, chatId, messageId, chat.strategy(), chat.messages().size(), content.length(), mcpServerIds);
            Mono<Prepared> preparation = Mono.defer(() -> layered && chat.strategy() == ContextStrategyType.FACTS
                            ? Mono.just(chat) : strategy.before(agent, chat, user))
                    .map(value -> new Prepared(value, null, false))
                    .onErrorResume(ChatFailure.class, failure -> summaryFallback(chat, failure));
            if (layered) preparation = preparation.flatMap(prepared -> agent.prepareMemory(prepared.chat().withWorkingMemory(
                            prepared.chat().workingMemory().withoutResolved(durable.resolvedProposals())), user, entries)
                    .map(memory -> new Prepared(prepared.chat().withWorkingMemory(memory), prepared.warning(), prepared.memoryFailed())));
            final Mono<Prepared> finalPreparation = preparation;
            boolean lifecycleActive = agent.definition().lifecycle().enabled() && layered;
            boolean invariantActive = agent.definition().invariants().enabled() && !chat.invariants().entries().isEmpty();
            boolean guardActive = lifecycleActive || invariantActive;
            Flux<StreamEvent> guarded;
            if (!guardActive) {
                guarded = phase(layered ? "UPDATING_MEMORY" : strategy.beforePhase(agent, chat),
                        finalPreparation.flatMapMany(prepared -> streamingAnswer(ownerId, agentId, messageId, agent,
                                strategy, prepared, user, entries, profile, layered, mcpServerIds)));
            } else {
                Mono<Agent.LifecycleCheck> lifecycleGuard = lifecycleActive
                        ? Mono.defer(() -> agent.checkLifecycle(chat, user, null))
                        : Mono.just(Agent.LifecycleCheck.allowed(null));
                guarded = phase(lifecycleActive ? "CHECKING_LIFECYCLE" : null,
                        lifecycleGuard.flatMapMany(lifecycleCheck -> {
                    if (!lifecycleCheck.allowed()) {
                        return rejectedLifecycle(ownerId, agentId, chat, user, lifecycleCheck, List.of());
                    }
                    Chat lifecycleChecked = chat.withLifecycle(chat.lifecycle()
                            .recordUsage(usage(lifecycleCheck.metrics())));
                    Mono<Agent.InvariantCheck> invariantGuard = invariantActive
                            ? Mono.defer(() -> agent.checkInvariants(lifecycleChecked, user, null))
                            : Mono.just(Agent.InvariantCheck.allowed(null));
                    return phase(invariantActive ? "CHECKING_INVARIANTS" : null,
                            invariantGuard.flatMapMany(invariantCheck -> {
                        if (!invariantCheck.allowed()) {
                            return rejected(ownerId, agentId, lifecycleChecked, user, invariantCheck, List.of());
                        }
                        Chat checked = lifecycleChecked.withInvariants(lifecycleChecked.invariants()
                                .recordUsage(usage(invariantCheck.metrics())));
                        Mono<Prepared> checkedPreparation = finalPreparation.map(value -> new Prepared(
                                value.chat().withLifecycle(checked.lifecycle()).withInvariants(checked.invariants()),
                                value.warning(), value.memoryFailed()));
                        return phase(layered ? "UPDATING_MEMORY" : strategy.beforePhase(agent, chat),
                                checkedPreparation.flatMapMany(prepared -> validatedAnswer(ownerId, agentId,
                                        messageId, agent, strategy, chat, prepared, user, entries, profile,
                                        layered, lifecycleActive, invariantActive, mcpServerIds)));
                    }));
                }));
            }
            return Flux.concat(Flux.just(StreamEvent.started(messageId)),
                            guarded)
                    .doOnError(error -> log.warn("agent_stream_failed agentId={} chatId={} requestId={} errorType={}",
                            agentId, chatId, messageId, error.getClass().getSimpleName()))
                    .doFinally(signal -> release(ownerId, chatId));
        } catch (RuntimeException error) { release(ownerId, chatId); throw error; }
    }
    private Flux<StreamEvent> streamingAnswer(String ownerId, String agentId, UUID messageId, Agent agent,
            ContextStrategy strategy, Prepared prepared, ChatMessage user, List<LongTermMemory.Entry> entries,
            UserProfile profile, boolean layered, List<String> mcpServerIds) {
        Flux<StreamEvent> answer = agent.answerStream(strategy.context(agent, prepared.chat()), user, entries,
                        profile, mcpServerIds)
                .concatMap(part -> {
            if (part.completed() == null) return Mono.just(StreamEvent.delta(part.delta()));
            Mono<Chat> appended = Mono.defer(() -> agent.completeMemory(prepared.chat(), user, part.completed()))
                    .map(memory -> prepared.chat().withWorkingMemory(memory).append(user, part.completed()));
            Flux<StreamEvent> completion = appended.flatMapMany(value -> finalizeTurn(ownerId, agentId,
                    messageId, agent, strategy, prepared, value, part.completed()));
            return phase(layered && !prepared.chat().workingMemory().goal().isBlank()
                    ? "SYNCING_QUESTIONS" : null, completion);
        });
        return mcpServerIds == null || mcpServerIds.isEmpty() ? answer
                : Flux.concat(Flux.just(StreamEvent.phase(StreamEvent.Type.USING_MCP)), answer);
    }
    private Flux<StreamEvent> validatedAnswer(String ownerId, String agentId, UUID messageId, Agent agent,
            ContextStrategy strategy, Chat original, Prepared prepared, ChatMessage user,
            List<LongTermMemory.Entry> entries, UserProfile profile, boolean layered,
            boolean lifecycleActive, boolean invariantActive, List<String> mcpServerIds) {
        Mono<Generated> generated = agent.answerStream(strategy.context(agent, prepared.chat()), user, entries,
                        profile, mcpServerIds)
                .collectList().map(Generated::from);
        Flux<StreamEvent> result = generated.flatMapMany(answer -> {
            Mono<Agent.LifecycleCheck> lifecycleGuard = lifecycleActive
                    ? agent.checkLifecycle(prepared.chat(), user, answer.completed())
                    : Mono.just(Agent.LifecycleCheck.allowed(null));
            return phase(lifecycleActive ? "VALIDATING_LIFECYCLE" : null,
                    lifecycleGuard.flatMapMany(lifecycleCheck -> {
                if (!lifecycleCheck.allowed()) {
                    Chat base = prepared.chat().withWorkingMemory(original.workingMemory()).withLifecycle(
                            prepared.chat().lifecycle().recordUsage(usage(answer.completed().metrics())));
                    return rejectedLifecycle(ownerId, agentId, base, user, lifecycleCheck, List.of());
                }
                Chat lifecycleChecked = prepared.chat().withLifecycle(prepared.chat().lifecycle()
                        .recordUsage(usage(lifecycleCheck.metrics())));
                Mono<Agent.InvariantCheck> invariantGuard = invariantActive
                        ? agent.checkInvariants(lifecycleChecked, user, answer.completed())
                        : Mono.just(Agent.InvariantCheck.allowed(null));
                return phase(invariantActive ? "VALIDATING_ANSWER" : null,
                        invariantGuard.flatMapMany(invariantCheck -> {
                    if (!invariantCheck.allowed()) {
                        Chat base = lifecycleChecked.withWorkingMemory(original.workingMemory()).withInvariants(
                                lifecycleChecked.invariants().recordUsage(usage(answer.completed().metrics())));
                        return rejected(ownerId, agentId, base, user, invariantCheck, List.of());
                    }
                    Chat withUsage = lifecycleChecked.withInvariants(lifecycleChecked.invariants()
                            .recordUsage(usage(invariantCheck.metrics())));
                    Mono<Chat> appended = Mono.defer(() -> agent.completeMemory(withUsage, user, answer.completed()))
                            .map(memory -> withUsage.withWorkingMemory(memory).append(user, answer.completed()));
                    Flux<StreamEvent> completion = appended.flatMapMany(value -> finalizeTurn(ownerId, agentId,
                            messageId, agent, strategy, prepared, value, answer.completed()));
                    Flux<StreamEvent> replay = Flux.fromIterable(answer.deltas()).map(StreamEvent::delta);
                    return Flux.concat(replay, phase(layered && !prepared.chat().workingMemory().goal().isBlank()
                            ? "SYNCING_QUESTIONS" : null, completion));
                }));
            }));
        });
        return Flux.concat(Flux.just(StreamEvent.phase(mcpServerIds == null || mcpServerIds.isEmpty()
                ? StreamEvent.Type.GENERATING : StreamEvent.Type.USING_MCP)), result);
    }
    private Flux<StreamEvent> finalizeTurn(String ownerId, String agentId, UUID messageId, Agent agent,
            ContextStrategy strategy, Prepared prepared, Chat value, ChatMessage completed) {
        Mono<Prepared> finalized = prepared.memoryFailed()
                ? Mono.just(new Prepared(value, prepared.warning(), true))
                : Mono.defer(() -> strategy.after(agent, value))
                    .map(result -> new Prepared(result, prepared.warning(), false))
                    .onErrorResume(ChatFailure.class, failure -> summaryFallback(value, failure));
        Mono<StreamEvent> event = finalized.map(result -> {
            repository.save(ownerId, result.chat());
            log.info("agent_stream_response agentId={} chatId={} requestId={} strategy={} totalTokens={} costUsd={}",
                    agentId, result.chat().id(), messageId, result.chat().strategy(),
                    completed.metrics() == null ? 0 : completed.metrics().totalTokens(),
                    completed.metrics() == null ? null : completed.metrics().totalCostUsd());
            return StreamEvent.completed(result.chat(), result.warning());
        });
        return phase(prepared.memoryFailed() ? null : strategy.afterPhase(agent, value), event.flux());
    }
    private Flux<StreamEvent> rejected(String ownerId, String agentId, Chat chat, ChatMessage user,
                                       Agent.InvariantCheck check, List<ChatMessage.Metrics> archived) {
        Chat base = chat.withInvariants(chat.invariants().recordUsage(archived));
        String text = refusal(base, check);
        var assistant = new ChatMessage(UUID.randomUUID(), ChatMessage.Role.ASSISTANT, text, Instant.now(), check.metrics());
        Chat saved = base.append(user, assistant);
        repository.save(ownerId, saved);
        log.info("agent_invariant_rejected agentId={} chatId={} requestId={} conflicts={}",
                agentId, chat.id(), user.id(), check.conflicts().size());
        return Flux.just(StreamEvent.delta(text), StreamEvent.completed(saved, "Ответ ограничен подтверждёнными инвариантами задачи."));
    }
    private Flux<StreamEvent> rejectedLifecycle(String ownerId, String agentId, Chat chat, ChatMessage user,
                                                Agent.LifecycleCheck check,
                                                List<ChatMessage.Metrics> archived) {
        Chat base = chat.withLifecycle(chat.lifecycle().recordUsage(archived));
        String text = lifecycleRefusal(base, check);
        var assistant = new ChatMessage(UUID.randomUUID(), ChatMessage.Role.ASSISTANT, text, Instant.now(), check.metrics());
        Chat saved = base.append(user, assistant);
        repository.save(ownerId, saved);
        log.info("agent_lifecycle_rejected agentId={} chatId={} requestId={} stage={} code={}",
                agentId, chat.id(), user.id(), chat.workingMemory().stage(), check.violation().code());
        return Flux.just(StreamEvent.delta(text), StreamEvent.completed(saved,
                "Действие заблокировано правилами жизненного цикла задачи."));
    }
    private static String lifecycleRefusal(Chat chat, Agent.LifecycleCheck check) {
        WorkingMemory memory = chat.workingMemory();
        String stage = switch (memory.stage()) {
            case REQUIREMENTS -> "Planning · сбор требований";
            case DESIGN -> "Execution · проектирование и реализация";
            case REVIEW -> "Validation · проверка";
            case DONE -> "Done · завершено";
        };
        return "Не могу выполнить это действие на текущем этапе задачи.\n\n"
                + "- **Текущий этап:** " + stage + "\n"
                + "- **Текущий шаг:** " + memory.currentStep() + "\n"
                + "- **Почему:** " + check.violation().explanation() + "\n\n"
                + "Сначала выполните ожидаемое действие в панели состояния: **"
                + expectedAction(memory.expectedAction()) + "**.";
    }
    private static String expectedAction(WorkingMemory.ExpectedAction action) {
        return switch (action) {
            case DEFINE_GOAL -> "опишите цель задачи";
            case PROVIDE_REQUIREMENTS -> "добавьте требования";
            case ANSWER_OPEN_QUESTIONS -> "закройте открытые вопросы";
            case CONFIRM_REQUIREMENTS -> "подтвердите требования";
            case RECORD_DECISIONS -> "зафиксируйте архитектурные решения";
            case CONFIRM_DESIGN -> "передайте решение на проверку";
            case VALIDATE_RESULT -> "проверьте результат и подтвердите завершение";
            case RESUME_TASK -> "продолжите задачу";
            case NONE -> "создайте новую задачу или измените требования";
        };
    }
    private static String refusal(Chat chat, Agent.InvariantCheck check) {
        var text = new StringBuilder("Не могу выполнить запрос в таком виде: он нарушает подтверждённые инварианты задачи.\n");
        for (var conflict : check.conflicts()) {
            var entry = chat.invariants().entries().stream().filter(value -> value.id().equals(conflict.invariantId()))
                    .findFirst().orElseThrow();
            text.append("\n- **").append(entry.title()).append(":** ").append(entry.rule())
                    .append(" — ").append(conflict.explanation());
        }
        return text.append("\n\nЕсли правило нужно пересмотреть, сначала измените или удалите его в панели инвариантов.").toString();
    }
    private static List<ChatMessage.Metrics> usage(ChatMessage.Metrics... values) {
        return java.util.Arrays.stream(values).filter(Objects::nonNull).toList();
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
    private static String key(String ownerId, UUID id) { return ownerId + ":" + id; }
    private void lock(String ownerId, UUID id) {
        synchronized (busyChats) {
            if (!busyChats.add(key(ownerId, id))) throw new ChatFailure(ChatFailure.Kind.BUSY, "В этом чате уже ожидается ответ. Повторите позже.");
        }
    }
    private void release(String ownerId, UUID id) { calls.release(); busyChats.remove(key(ownerId, id)); }
    private static boolean duplicate(Chat chat, UUID id, String content) {
        return switch (chat.matchUserMessage(id, content)) {
            case NONE -> false;
            case SAME -> true;
            case CONFLICT -> throw new ChatFailure(ChatFailure.Kind.INVALID, "Идентификатор сообщения уже использован");
        };
    }
    private record Prepared(Chat chat, String warning, boolean memoryFailed) { }
    private record Generated(List<String> deltas, ChatMessage completed) {
        static Generated from(List<Agent.AnswerPart> parts) {
            var completed = parts.stream().filter(part -> part.completed() != null).map(Agent.AnswerPart::completed).toList();
            if (completed.size() != 1) throw new ChatFailure(ChatFailure.Kind.PROVIDER,
                    "Поток ответа модели завершился некорректно. Повторите отправку.");
            return new Generated(parts.stream().filter(part -> part.delta() != null)
                    .map(Agent.AnswerPart::delta).toList(), completed.getFirst());
        }
    }
    public record StreamEvent(Type type, UUID messageId, String text, Chat chat, String warning) {
        public enum Type { STARTED, CHECKING_LIFECYCLE, CHECKING_INVARIANTS, GENERATING, USING_MCP,
            VALIDATING_LIFECYCLE, VALIDATING_ANSWER,
            SUMMARIZING, UPDATING_FACTS, UPDATING_MEMORY, SYNCING_QUESTIONS, DELTA, COMPLETED }
        static StreamEvent started(UUID id) { return new StreamEvent(Type.STARTED, id, null, null, null); }
        static StreamEvent phase(Type type) { return new StreamEvent(type, null, null, null, null); }
        static StreamEvent delta(String text) { return new StreamEvent(Type.DELTA, null, text, null, null); }
        static StreamEvent completed(Chat chat, String warning) { return new StreamEvent(Type.COMPLETED, null, null, chat, warning); }
    }
}
