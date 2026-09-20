package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.*;
import java.util.*;

/** Deterministic task rules. The model cannot confirm a stage or write long-term memory. */
public final class MemoryService {
    private static final TaskStateMachine STATE_MACHINE = new TaskStateMachine();
    private static final java.util.regex.Pattern TEMPORARY_GOAL_SUFFIX = java.util.regex.Pattern.compile(
            "(?iu)\\s*(?:,|;)?\\s*(?:и\\s+)?(?:без\\s+начала\\s+(?:реализации|разработки)|"
                    + "(?:реализацию|разработку|код|деплой)\\s+пока\\s+не\\s+(?:начинать|выполнять))"
                    + "[^.]*?(?:на\\s+текущем\\s+этапе|до\\s+(?:перехода|подтверждения\\s+(?:требований|плана)))\\.?$");
    private MemoryService() { }
    public record TaskData(String goal, Map<String, String> requirements, Map<String, String> constraints,
                           Map<String, String> decisions, List<String> openQuestions) { }
    public static WorkingMemory update(WorkingMemory old, TaskData data, String projectKey,
                                       List<WorkingMemory.Proposal> proposals, ChatMessage.Metrics metrics) {
        old = withoutTemporaryWorkflow(old);
        data = sanitize(data);
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
        memory = withoutTemporaryWorkflow(memory);
        return STATE_MACHINE.transition(memory, memory, TaskStateMachine.Event.CONFIRM_CURRENT_STAGE);
    }
    public static WorkingMemory pause(WorkingMemory memory) {
        memory = withoutTemporaryWorkflow(memory);
        return STATE_MACHINE.transition(memory, memory, TaskStateMachine.Event.PAUSE);
    }
    public static WorkingMemory resume(WorkingMemory memory) {
        memory = withoutTemporaryWorkflow(memory);
        return STATE_MACHINE.transition(memory, memory, TaskStateMachine.Event.RESUME);
    }

    /** Stage-scoped workflow commands are control input, not durable task requirements. */
    static boolean isTemporaryWorkflow(String value) {
        String text = value == null ? "" : value.strip().replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT);
        boolean temporal = text.contains("пока") || text.contains("на текущем этапе")
                || text.contains("текущий этап") || text.contains("до перехода")
                || text.contains("до подтверждения требований") || text.contains("до подтверждения плана")
                || text.contains("for now") || text.contains("at this stage") || text.contains("current stage")
                || text.contains("until the plan is approved") || text.contains("until requirements are approved")
                || text.contains("after the plan is approved") || text.contains("after requirements are approved");
        boolean action = text.contains("реализац") || text.contains("разработ") || text.contains("код")
                || text.contains("деплой") || text.contains("следующ") || text.contains("implement")
                || text.contains("coding") || text.contains("deploy") || text.contains("next stage");
        boolean instruction = text.contains("не начин") || text.contains("не приступ") || text.contains("не выполня")
                || text.contains("без начала") || text.contains("только после") || text.contains("запрещ")
                || text.contains("do not") || text.contains("don't") || text.contains("without")
                || text.contains("only after");
        return temporal && action && instruction;
    }

    private static TaskData sanitize(TaskData data) {
        return new TaskData(sanitizeGoal(data.goal()), durable(data.requirements()), durable(data.constraints()),
                durable(data.decisions()), data.openQuestions());
    }

    private static WorkingMemory withoutTemporaryWorkflow(WorkingMemory memory) {
        var sanitized = sanitize(new TaskData(memory.goal(), memory.requirements(), memory.constraints(),
                memory.decisions(), memory.openQuestions()));
        if (memory.goal().equals(sanitized.goal()) && memory.requirements().equals(sanitized.requirements())
                && memory.constraints().equals(sanitized.constraints()) && memory.decisions().equals(sanitized.decisions())) {
            return memory;
        }
        return new WorkingMemory(memory.version(), memory.stage(), memory.projectKey(), sanitized.goal(),
                sanitized.requirements(), sanitized.constraints(), sanitized.decisions(), sanitized.openQuestions(),
                memory.proposals(), memory.usage(), memory.lastExtraction(), memory.status(), null, null);
    }

    private static Map<String, String> durable(Map<String, String> values) {
        if (values == null || values.isEmpty()) return values == null ? Map.of() : values;
        var result = new TreeMap<String, String>();
        values.forEach((key, value) -> {
            String normalizedKey = key.strip().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            boolean workflowField = Set.of("current_deliverable", "current_stage", "expected_action",
                    "next_step", "workflow_instruction", "implementation_scope").contains(normalizedKey);
            if (!workflowField && !isTemporaryWorkflow(key + " " + value)) result.put(key, value);
        });
        return Collections.unmodifiableMap(result);
    }

    private static String sanitizeGoal(String goal) {
        if (goal == null || goal.isBlank()) return goal == null ? "" : goal.strip();
        String cleaned = TEMPORARY_GOAL_SUFFIX.matcher(goal.strip()).replaceFirst("").strip();
        return Arrays.stream(cleaned.split("(?<=[.!?])\\s+"))
                .filter(sentence -> !isTemporaryWorkflow(sentence))
                .collect(java.util.stream.Collectors.joining(" ")).strip();
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
