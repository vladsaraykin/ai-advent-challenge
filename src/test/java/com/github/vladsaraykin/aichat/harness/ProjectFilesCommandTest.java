package com.github.vladsaraykin.aichat.harness;

import com.github.vladsaraykin.aichat.harness.application.ProjectFilesCommand;
import com.github.vladsaraykin.aichat.harness.domain.ChatSettings;
import com.github.vladsaraykin.aichat.agent.application.ChatFailure;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProjectFilesCommandTest {
    @Test void recognizesOnlyExplicitLeadingCommand() {
        for (var content : List.of("/files", "/files Найди API", "/files\nОбнови документацию"))
            assertThat(ProjectFilesCommand.matches(content)).isTrue();
        for (var content : List.of("/filesOther", "Объясни /files", "/help Files"))
            assertThat(ProjectFilesCommand.matches(content)).isFalse();
        assertThat(ProjectFilesCommand.matches(null)).isFalse();
    }

    @Test void freezesSafeToolSelectionWithoutChangingPersistedPreferences() {
        var settings = new ChatSettings(7, true, List.of("filesystem", "project-files", "excel"), null,
                ChatSettings.LlmProvider.OPENAI, "gpt-5.6-sol", "high");
        var snapshot = ProjectFilesCommand.snapshot(settings);
        assertThat(snapshot.mcpServerIds()).containsExactly("project-files");
        assertThat(snapshot.ragEnabled()).isFalse();
        assertThat(snapshot.version()).isEqualTo(7);
        assertThat(snapshot.model()).isEqualTo(settings.model());
        assertThat(snapshot.reasoningEffort()).isEqualTo("high");
        assertThat(settings.ragEnabled()).isTrue();
        assertThat(settings.mcpServerIds()).contains("filesystem", "excel");
        assertThatThrownBy(() -> ProjectFilesCommand.snapshot(ChatSettings.defaults())).isInstanceOf(ChatFailure.class);
    }
}
