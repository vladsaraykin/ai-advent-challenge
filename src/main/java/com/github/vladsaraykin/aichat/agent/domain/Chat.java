package com.github.vladsaraykin.aichat.agent.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Chat(UUID id, String agentId, String title, Instant createdAt,
                   Instant updatedAt, ContextSummary summary, List<ChatMessage> messages,
                   ContextStrategyType strategy, ContextMemory memory, UUID parentChatId,
                   UUID checkpointId, List<Chat> branches, Boolean readOnly, WorkingMemory workingMemory,
                   TaskInvariants invariants) {
    public Chat(UUID id, String agentId, String title, Instant createdAt, Instant updatedAt,
                ContextSummary summary, List<ChatMessage> messages, ContextStrategyType strategy,
                ContextMemory memory, UUID parentChatId, UUID checkpointId, List<Chat> branches,
                Boolean readOnly, WorkingMemory workingMemory) {
        this(id, agentId, title, createdAt, updatedAt, summary, messages, strategy, memory, parentChatId,
                checkpointId, branches, readOnly, workingMemory, TaskInvariants.empty());
    }
    public Chat(UUID id, String agentId, String title, Instant createdAt, Instant updatedAt,
                ContextSummary summary, List<ChatMessage> messages, ContextStrategyType strategy,
                ContextMemory memory, UUID parentChatId, UUID checkpointId, List<Chat> branches, Boolean readOnly) {
        this(id, agentId, title, createdAt, updatedAt, summary, messages, strategy, memory, parentChatId,
                checkpointId, branches, readOnly, WorkingMemory.empty(), TaskInvariants.empty());
    }
    public Chat(UUID id, String agentId, String title, Instant createdAt,
                Instant updatedAt, ContextSummary summary, List<ChatMessage> messages) {
        this(id, agentId, title, createdAt, updatedAt, summary, messages,
                ContextStrategyType.SUMMARY, ContextMemory.empty(), null, null, List.of(), false);
    }
    public Chat(UUID id, String agentId, String title, Instant createdAt,
                Instant updatedAt, List<ChatMessage> messages) {
        this(id, agentId, title, createdAt, updatedAt, null, messages);
    }
    public Chat {
        messages = List.copyOf(messages);
        strategy = strategy == null ? ContextStrategyType.SUMMARY : strategy;
        memory = memory == null ? ContextMemory.empty() : memory;
        branches = branches == null ? List.of() : List.copyOf(branches);
        readOnly = Boolean.TRUE.equals(readOnly) || !branches.isEmpty();
        workingMemory = workingMemory == null ? WorkingMemory.empty() : workingMemory;
        invariants = invariants == null ? TaskInvariants.empty() : invariants;
    }
    public static Chat create(String agentId) {
        Instant now = Instant.now();
        return new Chat(UUID.randomUUID(), agentId, "Новый чат", now, now, null, List.of());
    }
    public static Chat create(String agentId, ContextStrategyType strategy) {
        return create(agentId).copy(null, List.of(), ContextMemory.empty(), strategy, null, null, List.of());
    }
    private Chat copy(ContextSummary nextSummary, List<ChatMessage> nextMessages, ContextMemory nextMemory,
                      ContextStrategyType type, UUID parent, UUID checkpoint, List<Chat> children) {
        return new Chat(id, agentId, title, createdAt, updatedAt, nextSummary, nextMessages,
                type, nextMemory, parent, checkpoint, children, readOnly, workingMemory, invariants);
    }
    public Chat withWorkingMemory(WorkingMemory value) {
        return new Chat(id, agentId, title, createdAt, Instant.now(), summary, messages, strategy, memory,
                parentChatId, checkpointId, branches, readOnly, value, invariants);
    }
    public Chat withInvariants(TaskInvariants value) {
        return new Chat(id, agentId, title, createdAt, Instant.now(), summary, messages, strategy, memory,
                parentChatId, checkpointId, branches, readOnly, workingMemory, value);
    }
    public Chat withMemory(ContextMemory value) {
        return copy(summary, messages, value, strategy, parentChatId, checkpointId, branches);
    }
    public Chat window(int retained) {
        int removed = Math.max(0, messages.size() - retained);
        removed -= removed % 2;
        return copy(summary, messages.subList(removed, messages.size()), memory.discard(messages.subList(0, removed)),
                strategy, parentChatId, checkpointId, branches);
    }
    /** Transient provider view only. Never replaces the persisted conversation. */
    public Chat contextWindow(int retained) {
        int start = Math.max(0, messages.size() - retained);
        start -= start % 2;
        return copy(summary, messages.subList(start, messages.size()), memory,
                strategy, parentChatId, checkpointId, branches);
    }
    public Chat withBranches(List<Chat> children) {
        return copy(summary, messages, memory, strategy, parentChatId, checkpointId, children);
    }
    public Chat fork(String first, String second) {
        UUID checkpoint = UUID.randomUUID();
        return copy(summary, messages, memory, strategy, parentChatId, checkpoint,
                List.of(child(first, checkpoint), child(second, checkpoint)));
    }
    private Chat child(String name, UUID checkpoint) {
        var inherited = messages.stream().map(m -> new ChatMessage(m.id(), m.role(), m.content(), m.createdAt(), null)).toList();
        Instant now = Instant.now();
        return new Chat(UUID.randomUUID(), agentId, name, now, now, null, inherited, strategy,
                ContextMemory.empty(), id, checkpoint, List.of(), false, workingMemory.inherited(), invariants.inherited());
    }
    public Chat append(ChatMessage user, ChatMessage assistant) {
        var updated = new java.util.ArrayList<>(messages);
        updated.add(user);
        updated.add(assistant);
        String nextTitle = messages.isEmpty() ? user.content().replaceAll("\\s+", " ").strip() : title;
        if (nextTitle.length() > 70) nextTitle = nextTitle.substring(0, 70) + "…";
        return new Chat(id, agentId, nextTitle, createdAt, assistant.createdAt(), summary, updated,
                strategy, memory, parentChatId, checkpointId, branches, readOnly, workingMemory, invariants);
    }
    public Chat compact(ContextSummary updatedSummary, int removedMessages) {
        if (removedMessages < 1 || removedMessages > messages.size()) {
            throw new IllegalArgumentException("Invalid compacted message count");
        }
        return copy(updatedSummary, messages.subList(removedMessages, messages.size()), memory,
                strategy, parentChatId, checkpointId, branches);
    }

    public ContextSummary.UserMessageMatch matchUserMessage(UUID messageId, String content) {
        var current = messages.stream().filter(message -> message.id().equals(messageId)).findFirst();
        if (current.isPresent()) {
            return current.get().role() == ChatMessage.Role.USER && current.get().content().equals(content)
                    ? ContextSummary.UserMessageMatch.SAME : ContextSummary.UserMessageMatch.CONFLICT;
        }
        return summary == null ? memory.match(messageId, content) : summary.match(messageId, content);
    }

    public int messageCount() {
        return messages.size() + memory.discardedMessages() + (summary == null ? 0 : summary.summarizedMessages());
    }
}
