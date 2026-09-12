package com.github.vladsaraykin.aichat.agent.domain;

import java.util.*;
import static com.github.vladsaraykin.aichat.agent.domain.ContextSummary.*;

/** Durable accounting survives deliberate removal of message text. */
public record ContextMemory(Map<String, String> facts, ArchivedUsage extractionUsage,
                            ArchivedUsage archivedUsage, int discardedMessages,
                            List<UserMessageFingerprint> fingerprints) {
    public ContextMemory {
        facts = facts == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(facts));
        extractionUsage = extractionUsage == null ? ArchivedUsage.empty() : extractionUsage;
        archivedUsage = archivedUsage == null ? ArchivedUsage.empty() : archivedUsage;
        fingerprints = fingerprints == null ? List.of() : List.copyOf(fingerprints);
    }
    public static ContextMemory empty() { return new ContextMemory(null, null, null, 0, null); }
    public ContextMemory updateFacts(Map<String, String> updated, ChatMessage.Metrics metrics) {
        var call = new ChatMessage(UUID.randomUUID(), ChatMessage.Role.ASSISTANT, "", java.time.Instant.now(), metrics);
        return new ContextMemory(updated, ArchivedUsage.add(extractionUsage, List.of(call)), archivedUsage,
                discardedMessages, fingerprints);
    }
    public ContextMemory discard(List<ChatMessage> messages) {
        var ids = new ArrayList<>(fingerprints);
        messages.stream().filter(m -> m.role() == ChatMessage.Role.USER)
                .map(m -> UserMessageFingerprint.from(m.id(), m.content())).forEach(ids::add);
        return new ContextMemory(facts, extractionUsage, ArchivedUsage.add(archivedUsage, messages),
                discardedMessages + messages.size(), ids);
    }
    public UserMessageMatch match(UUID id, String content) {
        return fingerprints.stream().filter(f -> f.id().equals(id)).findFirst()
                .map(f -> f.contentSha256().equals(UserMessageFingerprint.hash(content))
                        ? UserMessageMatch.SAME : UserMessageMatch.CONFLICT).orElse(UserMessageMatch.NONE);
    }
}
