package com.github.vladsaraykin.aichat.harness;

import com.github.vladsaraykin.aichat.harness.application.ProjectHelpCommand;
import com.github.vladsaraykin.aichat.harness.application.ProjectGitCommand;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectHelpCommandTest {
    @Test void parsesHelpQuestionAndAllowsBareCommand() {
        assertThat(ProjectHelpCommand.parse("/help Где описан запуск?"))
                .contains(new ProjectHelpCommand("Где описан запуск?"));
        var bare=ProjectHelpCommand.parse("/help").orElseThrow();
        assertThat(bare.question()).contains("назначение проекта");
    }

    @Test void doesNotInterpretCommandsWithLongerNamesAsHelp() {
        assertThat(ProjectHelpCommand.parse("/helper покажи пример")).isEmpty();
        assertThat(ProjectHelpCommand.parse("Скажи /help позже")).isEmpty();
    }

    @Test void recognizesCurrentBranchRequestsOnlyWhenGitMcpIsSelected() {
        assertThat(ProjectGitCommand.requestsCurrentBranch("Получи текущую git-ветку", Set.of("project-git"))).isTrue();
        assertThat(ProjectGitCommand.requestsCurrentBranch("Какая сейчас ветка?", Set.of("project-git"))).isTrue();
        assertThat(ProjectGitCommand.requestsCurrentBranch("Получи текущую git-ветку", Set.of())).isFalse();
        assertThat(ProjectGitCommand.requestsCurrentBranch("Расскажи, что такое git-ветка", Set.of("project-git"))).isFalse();
    }
}
