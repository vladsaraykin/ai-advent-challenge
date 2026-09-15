package com.github.vladsaraykin.aichat.agent.domain;

import java.time.Instant;
import java.util.*;

/** Explicitly confirmed memory for the single owner of this installation, isolated per agent. */
public record LongTermMemory(long version, List<Entry> entries, Set<UUID> resolvedProposals) {
    public LongTermMemory(long version, List<Entry> entries) { this(version, entries, Set.of()); }
    public LongTermMemory {
        entries = entries == null ? List.of() : List.copyOf(entries);
        resolvedProposals = resolvedProposals == null ? Set.of() : Set.copyOf(resolvedProposals);
        if (version < 0 || entries.size() > 100) throw new IllegalArgumentException("Invalid memory size/version");
    }
    public static LongTermMemory empty() { return new LongTermMemory(0, List.of()); }
    public List<Entry> relevant(String projectKey) {
        return entries.stream().filter(e -> e.scope() == WorkingMemory.Scope.GLOBAL
                || (!projectKey.isBlank() && projectKey.equals(e.projectKey()))).toList();
    }
    public record Entry(UUID id, WorkingMemory.Scope scope, String projectKey, String key, String value,
                        UUID sourceChatId, UUID sourceMessageId, Instant updatedAt) {
        public Entry {
            Objects.requireNonNull(id); Objects.requireNonNull(scope); Objects.requireNonNull(updatedAt);
            projectKey = WorkingMemory.text(projectKey, 80);
            key = WorkingMemory.text(key, 80); value = WorkingMemory.text(value, 500);
            if (key.isBlank() || value.isBlank() || (scope == WorkingMemory.Scope.PROJECT
                    && !projectKey.matches("[a-zA-Z0-9_-]{1,80}"))) throw new IllegalArgumentException("Invalid memory entry");
            if (scope == WorkingMemory.Scope.GLOBAL) projectKey = "";
        }
    }
}
