package com.github.vladsaraykin.aichat.harness;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.Chat;
import com.github.vladsaraykin.aichat.harness.application.*;
import com.github.vladsaraykin.aichat.harness.domain.ChatSettings;
import com.github.vladsaraykin.aichat.harness.infrastructure.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class HarnessRequestLoggingTest {
    @Test void rejectedPreparationIsLoggedWithoutPromptAndDoesNotReachModelOrRetrieval(CapturedOutput output) {
        var chats=mock(ChatService.class);var agents=mock(AgentCatalog.class);
        var settings=mock(ChatSettingsRepository.class);var requests=mock(HarnessRequestRepository.class);
        var rag=mock(RagContextService.class);var chat=Chat.create("assistant");
        var request=UUID.randomUUID();
        when(chats.get("vlad","assistant",chat.id())).thenReturn(chat);
        when(requests.find("vlad",request)).thenReturn(Optional.empty());
        when(settings.get("vlad","assistant",chat.id())).thenReturn(ChatSettings.defaults());
        var service=new HarnessService(chats,agents,settings,requests,rag);
        try {
            var events=service.stream("vlad","assistant",chat.id(),request,"/files SENSITIVE_USER_MESSAGE").collectList().block();
            assertThat(events.getLast().type()).isEqualTo("error");
            assertThat(output.getOut()).contains("harness_request_received", "harness_request_rejected",
                    "project_files_not_selected", "harness_request_failed", "phase=preparation", request.toString())
                    .doesNotContain("SENSITIVE_USER_MESSAGE");
            verifyNoInteractions(rag,agents);
            verify(requests,never()).create(any());
        } finally { service.close(); }
    }
}
