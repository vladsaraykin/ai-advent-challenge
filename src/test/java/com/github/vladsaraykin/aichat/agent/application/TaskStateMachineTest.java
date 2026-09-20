package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.WorkingMemory;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaskStateMachineTest {
    @Test void stateMachineUiCommandsNeverBecomeOpenQuestions() {
        var memory = MemoryService.update(WorkingMemory.empty(), new MemoryService.TaskData("Сервис",
                Map.of("channel", "email"), Map.of(), Map.of(), List.of()), "", List.of(), null);
        var updated = MemoryService.addQuestions(memory, List.of(
                "Подтвердите завершение этапа требований в панели памяти; после этого можно переходить к архитектуре.",
                "Нажмите кнопку «Продолжить задачу».",
                "Какой email-провайдер использовать?"), null);
        assertThat(updated.openQuestions()).containsExactly("Какой email-провайдер использовать?");
        assertThat(updated.expectedAction()).isEqualTo(WorkingMemory.ExpectedAction.ANSWER_OPEN_QUESTIONS);
    }

    @Test void exposesStageStepAndExpectedActionAndResumesWithoutLosingTaskData() {
        var initial = WorkingMemory.empty();
        assertThat(initial.stage()).isEqualTo(WorkingMemory.Stage.REQUIREMENTS);
        assertThat(initial.currentStep()).isEqualTo("Определить цель задачи");
        assertThat(initial.expectedAction()).isEqualTo(WorkingMemory.ExpectedAction.DEFINE_GOAL);

        var planned = MemoryService.update(initial, new MemoryService.TaskData("Сервис уведомлений",
                Map.of("channel", "email"), Map.of("language", "Java"), Map.of(), List.of()),
                "notifications", List.of(), null);
        assertThat(planned.expectedAction()).isEqualTo(WorkingMemory.ExpectedAction.CONFIRM_REQUIREMENTS);

        var executing = MemoryService.advance(planned);
        assertThat(executing.stage()).isEqualTo(WorkingMemory.Stage.DESIGN);
        assertThat(executing.expectedAction()).isEqualTo(WorkingMemory.ExpectedAction.RECORD_DECISIONS);

        var paused = MemoryService.pause(executing);
        assertThat(paused.status()).isEqualTo(WorkingMemory.Status.PAUSED);
        assertThat(paused.expectedAction()).isEqualTo(WorkingMemory.ExpectedAction.RESUME_TASK);
        assertThat(paused.goal()).isEqualTo("Сервис уведомлений");
        assertThatThrownBy(() -> MemoryService.advance(paused)).hasMessageContaining("продолжите");

        var resumed = MemoryService.resume(paused);
        assertThat(resumed.status()).isEqualTo(WorkingMemory.Status.ACTIVE);
        assertThat(resumed.stage()).isEqualTo(WorkingMemory.Stage.DESIGN);
        assertThat(resumed.requirements()).containsEntry("channel", "email");
        assertThat(resumed.expectedAction()).isEqualTo(WorkingMemory.ExpectedAction.RECORD_DECISIONS);
    }

    @Test void requirementChangeDeterministicallyReturnsExecutionToPlanningEvenWhilePaused() {
        var memory = MemoryService.update(WorkingMemory.empty(), new MemoryService.TaskData("Сервис",
                Map.of("channel", "email"), Map.of(), Map.of(), List.of()), "", List.of(), null);
        memory = MemoryService.advance(memory);
        memory = MemoryService.pause(memory);

        var changed = MemoryService.update(memory, new MemoryService.TaskData("Сервис",
                Map.of("channel", "email + sms"), Map.of(), Map.of(), List.of()), "", List.of(), null);
        assertThat(changed.stage()).isEqualTo(WorkingMemory.Stage.REQUIREMENTS);
        assertThat(changed.status()).isEqualTo(WorkingMemory.Status.PAUSED);
        assertThat(changed.expectedAction()).isEqualTo(WorkingMemory.ExpectedAction.RESUME_TASK);
    }

    @Test void temporaryWorkflowInstructionsAreNotDurableAndDoNotRollReviewBackToPlanning() {
        var polluted = new WorkingMemory(14, WorkingMemory.Stage.REVIEW, "notifications",
                "Спроектировать сервис и подготовить план без начала разработки на текущем этапе.",
                Map.of("channel", "email", "current_deliverable", "Подготовить план реализации"),
                Map.of("implementation_scope", "Реализацию на текущем этапе не начинать",
                        "stack", "Использовать Java 21"),
                Map.of("architecture", "Модульный монолит"), List.of(), List.of(), null, null,
                WorkingMemory.Status.ACTIVE, null, null);

        var cleaned = MemoryService.update(polluted, new MemoryService.TaskData(
                "Спроектировать сервис и подготовить план без начала разработки на текущем этапе.",
                polluted.requirements(), polluted.constraints(), polluted.decisions(), List.of()),
                polluted.projectKey(), List.of(), null);

        assertThat(cleaned.stage()).isEqualTo(WorkingMemory.Stage.REVIEW);
        assertThat(cleaned.goal()).isEqualTo("Спроектировать сервис и подготовить план");
        assertThat(cleaned.requirements()).containsOnly(entry("channel", "email"));
        assertThat(cleaned.constraints()).containsOnly(entry("stack", "Использовать Java 21"));

        var done = MemoryService.advance(cleaned);
        assertThat(done.stage()).isEqualTo(WorkingMemory.Stage.DONE);
        assertThat(done.constraints()).doesNotContainKey("implementation_scope");
    }

    @Test void recognizesEnglishStageScopedImplementationCommands() {
        assertThat(MemoryService.isTemporaryWorkflow("Do not implement at this stage")).isTrue();
        assertThat(MemoryService.isTemporaryWorkflow("Deploy only after the plan is approved")).isTrue();
        assertThat(MemoryService.isTemporaryWorkflow("Use PostgreSQL for durable storage")).isFalse();
    }
}
