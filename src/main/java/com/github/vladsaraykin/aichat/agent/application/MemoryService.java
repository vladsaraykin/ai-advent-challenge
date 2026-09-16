package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.*;
import java.util.*;

/** Deterministic task rules. The model cannot confirm a stage or write long-term memory. */
public final class MemoryService {
    private static final TaskStateMachine STATE_MACHINE = new TaskStateMachine();
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
                old.usage(), metrics == null ? old.lastExtraction() : metrics, old.status(), null, null);
        var usage = metrics == null ? old.usage() : ContextSummary.ArchivedUsage.add(old.usage(), List.of(
                new ChatMessage(UUID.randomUUID(), ChatMessage.Role.ASSISTANT, "", java.time.Instant.now(), metrics)));
        candidate = new WorkingMemory(candidate.version(), candidate.stage(), candidate.projectKey(), candidate.goal(), candidate.requirements(),
                candidate.constraints(), candidate.decisions(), candidate.openQuestions(), candidate.proposals(), usage,
                candidate.lastExtraction(), candidate.status(), null, null);
        return STATE_MACHINE.transition(old, candidate, TaskStateMachine.Event.TASK_DATA_UPDATED);
    }
    public static void checkVersion(long actual, long expected) {
        if (actual != expected) throw new ChatFailure(ChatFailure.Kind.BUSY, "Память уже изменилась. Обновите данные и повторите действие.");
    }
    public static WorkingMemory addQuestions(WorkingMemory old, List<String> newQuestions, ChatMessage.Metrics metrics) {
        var questions = new ArrayList<>(old.openQuestions());
        for (String question : newQuestions) {
            String normalized = question.strip().replaceAll("(?U)\\s+", " ");
            if (isWorkflowInstruction(normalized)) continue;
            if (questions.stream().noneMatch(q -> q.strip().replaceAll("(?U)\\s+", " ").equalsIgnoreCase(normalized)))
                questions.add(question);
        }
        return update(old, new TaskData(old.goal(), old.requirements(), old.constraints(), old.decisions(), questions),
                old.projectKey(), old.proposals(), metrics);
    }
    /** UI/state-machine commands are not unanswered domain questions and must never block a transition. */
    static boolean isWorkflowInstruction(String value) {
        String normalized = value == null ? "" : value.strip().replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT);
        boolean stageCommand = normalized.contains("этап") && (normalized.contains("подтверд")
                || normalized.contains("перейти") || normalized.contains("переход"));
        boolean uiCommand = (normalized.contains("панел") || normalized.contains("кнопк"))
                && (normalized.contains("подтверд") || normalized.contains("продолж")
                || normalized.contains("постав") || normalized.contains("нажм"));
        return stageCommand || uiCommand;
    }
    public static WorkingMemory advance(WorkingMemory memory) {
        return STATE_MACHINE.transition(memory, memory, TaskStateMachine.Event.CONFIRM_CURRENT_STAGE);
    }
    public static WorkingMemory pause(WorkingMemory memory) {
        return STATE_MACHINE.transition(memory, memory, TaskStateMachine.Event.PAUSE);
    }
    public static WorkingMemory resume(WorkingMemory memory) {
        return STATE_MACHINE.transition(memory, memory, TaskStateMachine.Event.RESUME);
    }
    public static WorkingMemory reject(WorkingMemory memory, UUID proposalId) {
        if (memory.proposals().stream().noneMatch(p -> p.id().equals(proposalId)))
            throw new ChatFailure(ChatFailure.Kind.NOT_FOUND, "Предложение не найдено");
        return new WorkingMemory(memory.version() + 1, memory.stage(), memory.projectKey(), memory.goal(), memory.requirements(),
                memory.constraints(), memory.decisions(), memory.openQuestions(),
                memory.proposals().stream().filter(p -> !p.id().equals(proposalId)).toList(), memory.usage(), memory.lastExtraction(),
                memory.status(), null, null);
    }
}
