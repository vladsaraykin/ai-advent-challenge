package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.*;
import java.util.List;

/** Deterministic lifecycle for an architect task. LLM output may update data, never transitions. */
public final class TaskStateMachine {
    public enum Event { TASK_DATA_UPDATED, CONFIRM_CURRENT_STAGE, PAUSE, RESUME }

    public WorkingMemory transition(WorkingMemory before, WorkingMemory candidate, Event event) {
        WorkingMemory.Stage stage = before.stage();
        WorkingMemory.Status status = before.status();
        switch (event) {
            case TASK_DATA_UPDATED -> {
                boolean requirementsChanged = !before.goal().equals(candidate.goal())
                        || !before.projectKey().equals(candidate.projectKey())
                        || !before.requirements().equals(candidate.requirements())
                        || !before.constraints().equals(candidate.constraints())
                        || !before.openQuestions().equals(candidate.openQuestions());
                if (requirementsChanged) stage = WorkingMemory.Stage.REQUIREMENTS;
                else if (!before.decisions().equals(candidate.decisions())
                        && (stage == WorkingMemory.Stage.DONE || stage == WorkingMemory.Stage.REVIEW)) {
                    stage = WorkingMemory.Stage.DESIGN;
                }
            }
            case CONFIRM_CURRENT_STAGE -> {
                if (status == WorkingMemory.Status.PAUSED) {
                    throw new ChatFailure(ChatFailure.Kind.INVALID, "Сначала продолжите приостановленную задачу.");
                }
                stage = advance(candidate);
            }
            case PAUSE -> {
                if (status == WorkingMemory.Status.PAUSED) return before;
                status = WorkingMemory.Status.PAUSED;
            }
            case RESUME -> {
                if (status == WorkingMemory.Status.ACTIVE) return before;
                status = WorkingMemory.Status.ACTIVE;
            }
        }
        long version = event == Event.TASK_DATA_UPDATED ? candidate.version() : before.version() + 1;
        WorkingMemory value = event == Event.TASK_DATA_UPDATED ? candidate : before;
        return copy(value, version, stage, status);
    }

    private WorkingMemory.Stage advance(WorkingMemory memory) {
        if (memory.goal().isBlank() || memory.requirements().isEmpty() || !memory.openQuestions().isEmpty()) {
            throw new ChatFailure(ChatFailure.Kind.INVALID,
                    "Заполните цель и требования, закройте открытые вопросы перед подтверждением этапа.");
        }
        return switch (memory.stage()) {
            case REQUIREMENTS -> WorkingMemory.Stage.DESIGN;
            case DESIGN -> {
                if (memory.decisions().isEmpty()) {
                    throw new ChatFailure(ChatFailure.Kind.INVALID, "Сначала зафиксируйте архитектурные решения.");
                }
                yield WorkingMemory.Stage.REVIEW;
            }
            case REVIEW -> WorkingMemory.Stage.DONE;
            case DONE -> throw new ChatFailure(ChatFailure.Kind.INVALID, "Задача уже завершена.");
        };
    }

    private WorkingMemory copy(WorkingMemory value, long version, WorkingMemory.Stage stage,
                               WorkingMemory.Status status) {
        return new WorkingMemory(version, stage, value.projectKey(), value.goal(), value.requirements(),
                value.constraints(), value.decisions(), value.openQuestions(), value.proposals(), value.usage(),
                value.lastExtraction(), status, null, null);
    }
}
