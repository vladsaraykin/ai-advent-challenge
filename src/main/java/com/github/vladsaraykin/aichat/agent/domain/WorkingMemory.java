package com.github.vladsaraykin.aichat.agent.domain;

import java.util.*;

/** Task-local state, committed with a completed conversation turn. */
public record WorkingMemory(long version, Stage stage, String projectKey, String goal,
                            Map<String, String> requirements, Map<String, String> constraints,
                            Map<String, String> decisions, List<String> openQuestions,
                            List<Proposal> proposals, ContextSummary.ArchivedUsage usage,
                            ChatMessage.Metrics lastExtraction) {
    public enum Stage { REQUIREMENTS, DESIGN, REVIEW, DONE }
    public enum Scope { GLOBAL, PROJECT }
    public record Proposal(UUID id, Scope scope, String key, String value, String evidence, UUID sourceMessageId) {
        public Proposal {
            Objects.requireNonNull(id); Objects.requireNonNull(scope); Objects.requireNonNull(sourceMessageId);
            key = text(key, 80); value = text(value, 500); evidence = text(evidence, 1000);
            if (key.isBlank() || value.isBlank() || evidence.isBlank()) throw new IllegalArgumentException("Empty proposal");
        }
    }
    public WorkingMemory {
        if (version < 0) throw new IllegalArgumentException("Invalid version");
        stage = stage == null ? Stage.REQUIREMENTS : stage;
        projectKey = text(projectKey, 80); goal = text(goal, 1000);
        if (!projectKey.isEmpty() && !projectKey.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid project key");
        requirements = boundedMap(requirements); constraints = boundedMap(constraints); decisions = boundedMap(decisions);
        openQuestions = openQuestions == null ? List.of() : openQuestions;
        if (openQuestions.size() > 20) throw new IllegalArgumentException("Too many questions");
        openQuestions.forEach(q -> {
            if (q == null || q.isBlank()) throw new IllegalArgumentException("Empty question");
            text(q, 500);
        });
        openQuestions = List.copyOf(openQuestions);
        proposals = proposals == null ? List.of() : List.copyOf(proposals);
        if (proposals.size() > 20) throw new IllegalArgumentException("Too many proposals");
        usage = usage == null ? ContextSummary.ArchivedUsage.empty() : usage;
    }
    public static String text(String value, int max) {
        value = value == null ? "" : value.strip();
        if (value.length() > max) throw new IllegalArgumentException("Memory value too long");
        return value;
    }
    public static Map<String, String> boundedMap(Map<String, String> input) {
        if (input == null) return Map.of();
        if (input.size() > 40) throw new IllegalArgumentException("Too many fields");
        var result = new TreeMap<String, String>();
        input.forEach((k, v) -> {
            if (k == null || v == null || k.isBlank() || v.isBlank()) throw new IllegalArgumentException("Empty memory field");
            result.put(text(k, 80), text(v, 500));
        });
        return Collections.unmodifiableMap(result);
    }
    public static WorkingMemory empty() { return new WorkingMemory(0, null, "", "", null, null, null, null, null, null, null); }
    public WorkingMemory inherited() {
        return new WorkingMemory(0, stage, projectKey, goal, requirements, constraints, decisions, openQuestions,
                List.of(), null, null);
    }
    public WorkingMemory withoutResolved(Set<UUID> ids) {
        return new WorkingMemory(version, stage, projectKey, goal, requirements, constraints, decisions, openQuestions,
                proposals.stream().filter(p -> !ids.contains(p.id())).toList(), usage, lastExtraction);
    }
}
