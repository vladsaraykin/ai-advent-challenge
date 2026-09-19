package com.github.vladsaraykin.aichat.agent.domain;

import java.time.Instant;
import java.util.*;

/** Explicit, user-managed task rules. They are never inferred or changed by the model. */
public record TaskInvariants(long version, List<Entry> entries, ContextSummary.ArchivedUsage usage) {
    public enum Type { ARCHITECTURE, TECHNICAL_DECISION, STACK_CONSTRAINT, BUSINESS_RULE }

    public record Entry(UUID id, Type type, String title, String rule, String rationale, Instant createdAt) {
        public Entry {
            Objects.requireNonNull(id);
            Objects.requireNonNull(type);
            Objects.requireNonNull(createdAt);
            title = bounded(title, 100, true);
            rule = bounded(rule, 1000, true);
            rationale = bounded(rationale, 500, false);
        }
    }

    public TaskInvariants {
        if (version < 0) throw new IllegalArgumentException("Invalid invariant version");
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (entries.size() > 40 || entries.stream().map(Entry::id).distinct().count() != entries.size()) {
            throw new IllegalArgumentException("Invalid invariant entries");
        }
        usage = usage == null ? ContextSummary.ArchivedUsage.empty() : usage;
    }

    public static TaskInvariants empty() { return new TaskInvariants(0, List.of(), null); }

    public TaskInvariants put(Entry value) {
        var next = new ArrayList<>(entries);
        int index = java.util.stream.IntStream.range(0, next.size())
                .filter(i -> next.get(i).id().equals(value.id())).findFirst().orElse(-1);
        if (index >= 0) next.set(index, value); else next.add(value);
        return new TaskInvariants(version + 1, next, usage);
    }

    public TaskInvariants delete(UUID id) {
        if (entries.stream().noneMatch(entry -> entry.id().equals(id))) {
            throw new IllegalArgumentException("Invariant not found");
        }
        return new TaskInvariants(version + 1, entries.stream().filter(entry -> !entry.id().equals(id)).toList(), usage);
    }

    public TaskInvariants recordUsage(List<ChatMessage.Metrics> metrics) {
        var messages = metrics.stream().filter(Objects::nonNull).map(value ->
                new ChatMessage(UUID.randomUUID(), ChatMessage.Role.ASSISTANT, "", Instant.now(), value)).toList();
        return messages.isEmpty() ? this : new TaskInvariants(version, entries,
                ContextSummary.ArchivedUsage.add(usage, messages));
    }

    public TaskInvariants inherited() { return new TaskInvariants(0, entries, null); }

    private static String bounded(String value, int max, boolean required) {
        value = value == null ? "" : value.strip();
        if ((required && value.isBlank()) || value.length() > max) throw new IllegalArgumentException("Invalid invariant text");
        return value;
    }
}
