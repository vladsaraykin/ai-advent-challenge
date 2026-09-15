package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.*;
import java.util.*;

/** Deterministic task rules. The model cannot confirm a stage or write long-term memory. */
public final class MemoryService {
    private MemoryService() { }
    public record TaskData(String goal, Map<String, String> requirements, Map<String, String> constraints,
                           Map<String, String> decisions, List<String> openQuestions) { }
    public static WorkingMemory update(WorkingMemory old, TaskData data, String projectKey,
                                       List<WorkingMemory.Proposal> proposals, ChatMessage.Metrics metrics) {
        if (!old.projectKey().isBlank() && !old.projectKey().equals(projectKey)) {
            proposals = proposals.stream().filter(p -> p.scope() == WorkingMemory.Scope.GLOBAL).toList();
        }
        var candidate = new WorkingMemory(old.version() + 1, old.stage(), projectKey, data.goal(),
                data.requirements(), data.constraints(), data.decisions(), data.openQuestions(), proposals,
                old.usage(), metrics == null ? old.lastExtraction() : metrics);
        boolean requirementsChanged = !old.goal().equals(candidate.goal())
                || !old.projectKey().equals(candidate.projectKey())
                || !old.requirements().equals(candidate.requirements()) || !old.constraints().equals(candidate.constraints())
                || !old.openQuestions().equals(candidate.openQuestions());
        var stage = requirementsChanged ? WorkingMemory.Stage.REQUIREMENTS : old.stage();
        if (!old.decisions().equals(candidate.decisions()) && (stage == WorkingMemory.Stage.DONE || stage == WorkingMemory.Stage.REVIEW)) {
            stage = WorkingMemory.Stage.DESIGN;
        }
        var usage = metrics == null ? old.usage() : ContextSummary.ArchivedUsage.add(old.usage(), List.of(
                new ChatMessage(UUID.randomUUID(), ChatMessage.Role.ASSISTANT, "", java.time.Instant.now(), metrics)));
        return new WorkingMemory(candidate.version(), stage, candidate.projectKey(), candidate.goal(), candidate.requirements(),
                candidate.constraints(), candidate.decisions(), candidate.openQuestions(), candidate.proposals(), usage,
                candidate.lastExtraction());
    }
    public static void checkVersion(long actual, long expected) {
        if (actual != expected) throw new ChatFailure(ChatFailure.Kind.BUSY, "Память уже изменилась. Обновите данные и повторите действие.");
    }
    public static WorkingMemory addQuestions(WorkingMemory old, List<String> newQuestions, ChatMessage.Metrics metrics) {
        var questions = new ArrayList<>(old.openQuestions());
        for (String question : newQuestions) {
            String normalized = question.strip().replaceAll("(?U)\\s+", " ");
            if (questions.stream().noneMatch(q -> q.strip().replaceAll("(?U)\\s+", " ").equalsIgnoreCase(normalized)))
                questions.add(question);
        }
        return update(old, new TaskData(old.goal(), old.requirements(), old.constraints(), old.decisions(), questions),
                old.projectKey(), old.proposals(), metrics);
    }
    public static WorkingMemory advance(WorkingMemory memory) {
        if (memory.goal().isBlank() || memory.requirements().isEmpty() || !memory.openQuestions().isEmpty()) {
            throw new ChatFailure(ChatFailure.Kind.INVALID, "Заполните цель и требования, закройте открытые вопросы перед подтверждением этапа.");
        }
        var stage = switch (memory.stage()) {
            case REQUIREMENTS -> WorkingMemory.Stage.DESIGN;
            case DESIGN -> {
                if (memory.decisions().isEmpty()) throw new ChatFailure(ChatFailure.Kind.INVALID, "Сначала зафиксируйте архитектурные решения.");
                yield WorkingMemory.Stage.REVIEW;
            }
            case REVIEW -> WorkingMemory.Stage.DONE;
            case DONE -> throw new ChatFailure(ChatFailure.Kind.INVALID, "Задача уже завершена.");
        };
        return new WorkingMemory(memory.version() + 1, stage, memory.projectKey(), memory.goal(), memory.requirements(),
                memory.constraints(), memory.decisions(), memory.openQuestions(), memory.proposals(), memory.usage(), memory.lastExtraction());
    }
    public static WorkingMemory reject(WorkingMemory memory, UUID proposalId) {
        if (memory.proposals().stream().noneMatch(p -> p.id().equals(proposalId)))
            throw new ChatFailure(ChatFailure.Kind.NOT_FOUND, "Предложение не найдено");
        return new WorkingMemory(memory.version() + 1, memory.stage(), memory.projectKey(), memory.goal(), memory.requirements(),
                memory.constraints(), memory.decisions(), memory.openQuestions(),
                memory.proposals().stream().filter(p -> !p.id().equals(proposalId)).toList(), memory.usage(), memory.lastExtraction());
    }
}
