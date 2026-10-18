package com.github.vladsaraykin.aichat.agent.application;

import com.github.vladsaraykin.aichat.agent.domain.WorkingMemory;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaskStateMachineTest {
    @Test void advisoryProgressDoesNotRequireManuallyFilledFieldsOrResolvedChatQuestions() {
        var initial=WorkingMemory.empty();
        var executing=MemoryService.advance(initial,true);
        assertThat(executing.stage()).isEqualTo(WorkingMemory.Stage.DESIGN);
        var questioning=MemoryService.update(executing,new MemoryService.TaskData("Docs",Map.of(),Map.of(),
                Map.of(),List.of("Какую версию API описать?")),"",List.of(),null);
        var review=MemoryService.advance(MemoryService.advance(questioning,true),true);
        assertThat(review.stage()).isEqualTo(WorkingMemory.Stage.REVIEW);
        assertThat(review.openQuestions()).containsExactly("Какую версию API описать?");
        assertThatThrownBy(()->MemoryService.advance(MemoryService.pause(review),true)).hasMessageContaining("продолжите");
        assertThat(AgentContextBuilder.memoryPrompt(review,List.of(),true)).contains("Уточняющие вопросы задавай прямо",
                "Списки requirements, decisions, openQuestions и expectedAction не блокируют работу",
                "Planning и Done");
    }
    @Test void genericPlanApprovalIsNotAnOpenQuestionButDomainQuestionsRemain() {
        for (String instruction : List.of("Пожалуйста, подтвердите этот план или внесите правки.",
                "Пожалуйста подтвердите этот план или внесите правки.", "Подтвердите предложенный план.",
                "Утвердите план действий или укажите необходимые изменения.", "Подтвердите требования.",
                "Please confirm the plan or suggest changes.")) {
            assertThat(MemoryService.isWorkflowInstruction(instruction)).as(instruction).isTrue();
            var memory=MemoryService.update(WorkingMemory.empty(),new MemoryService.TaskData("Docs",
                    Map.of("result","Markdown"),Map.of(),Map.of(),List.of()),"",List.of(),null);
            assertThat(MemoryService.addQuestions(memory,List.of(instruction),null).openQuestions()).isEmpty();
        }
        for (String question : List.of("Какой срок хранения данных указать в плане?",
                "Подтвердите требования к резервному копированию?",
                "Подтвердите план восстановления базы данных или внесите правки.",
                "Подтвердите, что API должен работать без авторизации?",
                "Please confirm the plan for database recovery.")) {
            assertThat(MemoryService.isWorkflowInstruction(question)).as(question).isFalse();
        }
    }

    @Test void confirmationCleansLegacyApprovalQuestionsWithoutDroppingRealQuestionsOrRequirementsGate() {
        var legacy=new WorkingMemory(9,WorkingMemory.Stage.REQUIREMENTS,"docs","Обновить README",
                Map.of("format","Markdown"),Map.of(),Map.of(),List.of("Пожалуйста, подтвердите этот план или внесите правки."),
                List.of(),null,null,WorkingMemory.Status.ACTIVE,null,null);
        var advanced=MemoryService.advance(legacy);
        assertThat(advanced.stage()).isEqualTo(WorkingMemory.Stage.DESIGN);
        assertThat(advanced.openQuestions()).isEmpty();
        assertThat(advanced.version()).isEqualTo(10);
        assertThat(advanced.requirements()).isEqualTo(legacy.requirements());
        var mixed=new WorkingMemory(9,WorkingMemory.Stage.REQUIREMENTS,"docs",legacy.goal(),legacy.requirements(),
                Map.of(),Map.of(),List.of(legacy.openQuestions().getFirst(),"Какой формат документа нужен?"),
                List.of(),null,null,WorkingMemory.Status.ACTIVE,null,null);
        assertThatThrownBy(()->MemoryService.advance(mixed)).hasMessageContaining("Остались открытые вопросы");
        assertThat(mixed.openQuestions()).hasSize(2);
        var edited=MemoryService.update(mixed,new MemoryService.TaskData(mixed.goal(),mixed.requirements(),Map.of(),
                Map.of(),mixed.openQuestions()),"docs",List.of(),null);
        assertThat(edited.openQuestions()).containsExactly("Какой формат документа нужен?");
        var noRequirements=new WorkingMemory(9,WorkingMemory.Stage.REQUIREMENTS,"docs",legacy.goal(),Map.of(),
                Map.of(),Map.of(),legacy.openQuestions(),List.of(),null,null,WorkingMemory.Status.ACTIVE,null,null);
        assertThatThrownBy(()->MemoryService.advance(noRequirements)).hasMessageContaining("Заполните цель и требования");
    }
    @Test void explicitReturnReopensReviewAndDoneWithoutLosingMemoryOrUsage() {
        var planned = MemoryService.update(WorkingMemory.empty(), new MemoryService.TaskData("Обновить документацию",
                Map.of("format", "Markdown"), Map.of("stack", "Java"), Map.of("target", "README"), List.of(),
                Map.of("API", "HTTP")), "project", List.of(), null);
        var executing = MemoryService.advance(planned);
        var review = MemoryService.advance(executing);
        for (var before : List.of(review, MemoryService.advance(review))) {
            var reopened = MemoryService.returnToExecution(before);
            assertThat(reopened.stage()).isEqualTo(WorkingMemory.Stage.DESIGN);
            assertThat(reopened.status()).isEqualTo(WorkingMemory.Status.ACTIVE);
            assertThat(reopened.version()).isEqualTo(before.version() + 1);
            assertThat(reopened.goal()).isEqualTo(before.goal());
            assertThat(reopened.projectKey()).isEqualTo(before.projectKey());
            assertThat(reopened.requirements()).isEqualTo(before.requirements());
            assertThat(reopened.constraints()).isEqualTo(before.constraints());
            assertThat(reopened.decisions()).isEqualTo(before.decisions());
            assertThat(reopened.terms()).isEqualTo(before.terms());
            assertThat(reopened.usage()).isEqualTo(before.usage());
            assertThat(reopened.proposals()).isEqualTo(before.proposals());
            assertThat(reopened.expectedAction()).isEqualTo(WorkingMemory.ExpectedAction.CONFIRM_DESIGN);
            assertThatThrownBy(() -> MemoryService.returnToExecution(MemoryService.pause(before)))
                    .hasMessageContaining("продолжите");
        }
        assertThatThrownBy(() -> MemoryService.returnToExecution(planned)).hasMessageContaining("Validation или Done");
        assertThatThrownBy(() -> MemoryService.returnToExecution(executing)).hasMessageContaining("Validation или Done");
    }

    @Test void savingDecisionsDoesNotFinishExecutionAndPromptNamesRealUiControls() {
        var memory=MemoryService.update(WorkingMemory.empty(), new MemoryService.TaskData("Docs",
                Map.of("format", "Markdown"), Map.of(), Map.of(), List.of()), "", List.of(), null);
        memory=MemoryService.advance(memory);
        memory=MemoryService.update(memory, new MemoryService.TaskData("Docs", memory.requirements(), Map.of(),
                Map.of("target", "README"), List.of()), "", List.of(), null);
        assertThat(memory.stage()).isEqualTo(WorkingMemory.Stage.DESIGN);
        assertThat(AgentContextBuilder.memoryPrompt(memory,List.of())).contains("Зафиксировать решения", "Вернуть в выполнение",
                "Сохранение данных не подтверждает завершение");
    }
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
