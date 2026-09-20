package com.github.vladsaraykin.aichat.agent.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Usage produced by semantic lifecycle guards; the authoritative state remains WorkingMemory. */
public record TaskLifecycle(ContextSummary.ArchivedUsage usage) {
    public TaskLifecycle {
        usage = usage == null ? ContextSummary.ArchivedUsage.empty() : usage;
    }

    public static TaskLifecycle empty() { return new TaskLifecycle(null); }

    public TaskLifecycle recordUsage(List<ChatMessage.Metrics> metrics) {
        var messages = metrics.stream().map(value -> new ChatMessage(UUID.randomUUID(),
                ChatMessage.Role.ASSISTANT, "", Instant.now(), value)).toList();
        return new TaskLifecycle(ContextSummary.ArchivedUsage.add(usage, messages));
    }

    /** A branch owns only the guard calls performed after the checkpoint. */
    public TaskLifecycle inherited() { return empty(); }
}
