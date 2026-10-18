package com.github.vladsaraykin.aichat.mcp.application;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.Chat;
import com.github.vladsaraykin.aichat.harness.domain.*;
import com.github.vladsaraykin.aichat.harness.infrastructure.HarnessRequestRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectFilesApprovalTest {
    @Test void fileToolsCannotRunDuringPlanningOrPauseAndRequireRequestContext() {
        var jdbc=mock(JdbcTemplate.class);var tx=mock(TransactionTemplate.class);
        var requests=mock(HarnessRequestRepository.class);var chats=mock(ChatRepository.class);
        var chat=Chat.create("assistant");
        var context=new RequestContext("vlad","assistant",chat.id(),UUID.randomUUID(),"/files Обнови docs",
                new ChatSettings(0,false,List.of("project-files"),null),null,null,null);
        when(requests.find("vlad",context.messageId())).thenReturn(Optional.of(
                new HarnessRequestRepository.RequestView(context,"RUNNING",List.of(),true)));
        when(chats.get("vlad","assistant",chat.id())).thenReturn(chat);
        var delegate=mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(ToolDefinition.builder().name("project_write_document")
                .description("Write document").inputSchema("{\"type\":\"object\"}").build());
        var approvals=new McpApprovalService(jdbc,tx,requests,chats);
        var guarded=approvals.guard("project-files",delegate,context);
        assertThat(guarded.call("{}")).contains("Execution/Validation");
        when(chats.get("vlad","assistant",chat.id())).thenReturn(chat.withWorkingMemory(MemoryService.pause(chat.workingMemory())));
        assertThatThrownBy(() -> guarded.call("{}")).isInstanceOf(ChatFailure.class).hasMessageContaining("Продолжите");
        assertThatThrownBy(() -> approvals.guard("project-files",delegate,null)).isInstanceOf(ChatFailure.class);
        verify(delegate,never()).call(anyString(),any());
        verifyNoInteractions(jdbc,tx);
    }
}
