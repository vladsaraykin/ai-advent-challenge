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
}
