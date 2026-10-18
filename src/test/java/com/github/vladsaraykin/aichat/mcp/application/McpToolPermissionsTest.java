package com.github.vladsaraykin.aichat.mcp.application;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.harness.domain.*;
import com.github.vladsaraykin.aichat.harness.infrastructure.HarnessRequestRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpToolPermissionsTest {
    @Test void advisoryPlanningAndDoneStillRequireApprovalAndNeverBypassPauseOrUncertainResults() throws Exception {
        for (var stage : List.of(WorkingMemory.Stage.REQUIREMENTS,WorkingMemory.Stage.DONE)) {
            var f=new Fixture("AWAITING_APPROVAL",false,"{}");
            var catalog=mock(AgentCatalog.class);var agent=mock(Agent.class);
            var definition=new com.github.vladsaraykin.aichat.agent.infrastructure.AgentRegistry(
                    mock(ConversationModel.class),"classpath:agents/*.yaml").get("assistant").definition();
            assertThat(definition.lifecycle().advisory()).isTrue();
            when(agent.definition()).thenReturn(definition);when(catalog.get("assistant")).thenReturn(agent);
            @SuppressWarnings("unchecked") var provider=(org.springframework.beans.factory.ObjectProvider<AgentCatalog>)
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            when(provider.getObject()).thenReturn(catalog);
            var service=new McpApprovalService(f.jdbc,f.tx,f.requests,f.chats,McpCallBudget.defaults(),provider);
            var memory=f.chat.workingMemory();
            var staged=f.chat.withWorkingMemory(new WorkingMemory(memory.version(),stage,memory.projectKey(),
                    "",Map.of(),Map.of(),Map.of(),List.of("Какую версию описать?"),List.of(),null,null,
                    WorkingMemory.Status.ACTIVE,null,null));
            when(f.chats.get("alice","assistant",f.chat.id())).thenReturn(staged);
            // Return the existing awaiting call without relying on this test's SQL mapper.
            doReturn(new McpApprovalService.Call(f.callId,"project-files","write",
                    "{}","AWAITING_APPROVAL",1,null,f.signature)).when(f.tx).execute(any());
            assertThatThrownBy(()->service.guard("project-files",f.delegate,f.context).call("{}"))
                    .isInstanceOf(McpApprovalService.Required.class);
            verify(f.delegate,never()).call(anyString(),any());
            doReturn(new McpApprovalService.Call(f.callId,"project-files","write",
                    "{}","APPROVED",1,null,f.signature)).when(f.tx).execute(any());
            assertThat(service.guard("project-files",f.delegate,f.context).call("{}")).contains("saved");
            doReturn(new McpApprovalService.Call(f.callId,"project-files","write",
                    "{}","UNCERTAIN",1,null,f.signature)).when(f.tx).execute(any());
            assertThatThrownBy(()->service.guard("project-files",f.delegate,f.context).call("{}"))
                    .hasMessageContaining("автоматический повтор запрещён");
            verify(f.delegate,times(1)).call(anyString(),any());
            when(f.chats.get("alice","assistant",f.chat.id())).thenReturn(
                    staged.withWorkingMemory(MemoryService.pause(staged.workingMemory())));
            assertThatThrownBy(()->service.guard("project-files",f.delegate,f.context).call("{}"))
                    .hasMessageContaining("Продолжите");
        }
    }
    @Test void savedGrantAllowsNewParametersAndRecordsTheInvocation() {
        var f=new Fixture("AWAITING_APPROVAL",true,"{\"path\":\"second.md\"}");
        assertThat(f.guarded().call(f.input)).contains("saved");
        verify(f.delegate).call(eq(f.input),isNull());
        verify(f.jdbc).update(startsWith("UPDATE rag.mcp_calls SET status='APPROVED'"),eq(f.callId),eq("alice"));
        verify(f.jdbc).update(contains("status='RUNNING'"),eq(f.callId),eq("alice"));
    }

    @Test void absentPermissionStillRequiresExplicitApproval() {
        var f=new Fixture("AWAITING_APPROVAL",false,"{}");
        assertThatThrownBy(()->f.guarded().call(f.input)).isInstanceOf(McpApprovalService.Required.class);
        verify(f.delegate,never()).call(anyString(),any());
    }

    @Test void permissionLookupIsBoundToOwnerChatServerToolAndSchema() {
        var f=new Fixture("AWAITING_APPROVAL",true,"{}");
        f.guarded().call(f.input);
        verify(f.jdbc).queryForList(contains("FROM rag.mcp_tool_permissions"),eq(String.class),eq("alice"),
                eq(f.chat.id()),eq("project-files"),eq("write"),eq(f.signature));
        var other=new Fixture("AWAITING_APPROVAL",false,"{}");
        assertThatThrownBy(()->other.guarded().call(other.input)).isInstanceOf(McpApprovalService.Required.class);
    }

    @Test void grantsDoNotRepeatUnknownWritesOrOverrideExplicitRejection() {
        for(var status:List.of("UNCERTAIN","RUNNING","REJECTED","SUCCEEDED")) {
            var f=new Fixture(status,true,"{}");
            if(Set.of("UNCERTAIN","RUNNING").contains(status))
                assertThatThrownBy(()->f.guarded().call(f.input)).isInstanceOf(ChatFailure.class).hasMessageContaining("автоматический повтор запрещён");
            else assertThat(f.guarded().call(f.input)).contains(status.equals("SUCCEEDED") ? "cached" : "отклонил");
            verify(f.delegate,never()).call(anyString(),any());
        }
    }

    @Test void persistentGrantNeverBypassesPausedTask() {
        var f=new Fixture("AWAITING_APPROVAL",true,"{}");
        when(f.chats.get("alice","assistant",f.chat.id())).thenReturn(f.chat.withWorkingMemory(MemoryService.pause(f.chat.workingMemory())));
        assertThatThrownBy(()->f.guarded().call(f.input)).isInstanceOf(ChatFailure.class).hasMessageContaining("Продолжите");
        verify(f.delegate,never()).call(anyString(),any());
    }

    @Test void changedSchemaCannotExecuteAnAlreadyApprovedOldCall() {
        var f=new Fixture("APPROVED",true,"{}");
        when(f.delegate.getToolDefinition()).thenReturn(definition("Changed description", "{\"type\":\"object\"}"));
        assertThatThrownBy(()->f.guarded().call(f.input)).isInstanceOf(ChatFailure.class).hasMessageContaining("Описание инструмента изменилось");
        verify(f.delegate,never()).call(anyString(),any());
    }

    @Test void signatureIsStableForSchemaKeyOrderAndChangesWithItsContract() {
        var service=new McpApprovalService(null,null,null,null);
        assertThat(service.signature(definition("Write", "{\"type\":\"object\",\"properties\":{}}")))
                .isEqualTo(service.signature(definition("Write", "{\"properties\":{},\"type\":\"object\"}")))
                .isNotEqualTo(service.signature(definition("Read", "{\"type\":\"object\"}")));
    }

    @Test void revokeIsScopedAndRejectCannotGrantPermission() {
        var jdbc=mock(JdbcTemplate.class);
        var service=new McpApprovalService(jdbc,null,null,null);
        var chat=UUID.randomUUID();service.revoke("alice",chat,"files","write");
        verify(jdbc).update(contains("DELETE FROM rag.mcp_tool_permissions"),eq("alice"),eq(chat),eq("files"),eq("write"));
        assertThatThrownBy(()->service.decide("alice",UUID.randomUUID(),UUID.randomUUID(),false,true)).isInstanceOf(ChatFailure.class);
    }

    @Test void toolsWithoutOptionalDescriptionsStillHaveAnApprovalSignature() {
        var definition=mock(ToolDefinition.class);
        when(definition.name()).thenReturn("read");
        when(definition.inputSchema()).thenReturn("{\"type\":\"object\"}");
        assertThat(new McpApprovalService(null,null,null,null).signature(definition)).hasSize(64);
    }

    private static ToolDefinition definition(String description,String schema) {
        return ToolDefinition.builder().name("write").description(description).inputSchema(schema).build();
    }
    private static class Fixture {
        final JdbcTemplate jdbc=mock(JdbcTemplate.class);
        final TransactionTemplate tx=mock(TransactionTemplate.class);
        final HarnessRequestRepository requests=mock(HarnessRequestRepository.class);
        final ChatRepository chats=mock(ChatRepository.class);
        final ToolCallback delegate=mock(ToolCallback.class);
        final Chat chat;
        final UUID request=UUID.randomUUID(),callId=UUID.randomUUID();
        final McpApprovalService service;
        final String signature,input;
        final RequestContext context;
        @SuppressWarnings("unchecked") Fixture(String status,boolean granted,String input) {
            this.input=input;
            var base=Chat.create("assistant");
            var task=MemoryService.update(base.workingMemory(),new MemoryService.TaskData("Update docs",Map.of("format","Markdown"),Map.of(),Map.of(),List.of()),"",List.of(),null);
            chat=base.withWorkingMemory(MemoryService.advance(task));
            context=new RequestContext("alice","assistant",chat.id(),request,"/files Update docs",
                    new ChatSettings(0,false,List.of("project-files"),null),null,null,null);
            service=spy(new McpApprovalService(jdbc,tx,requests,chats));
            var definition=definition("Write", "{\"type\":\"object\"}");signature=service.signature(definition);
            when(delegate.getToolDefinition()).thenReturn(definition);
            when(delegate.call(anyString(),any())).thenReturn("saved");
            when(requests.find("alice",request)).thenReturn(Optional.of(new HarnessRequestRepository.RequestView(context,"RUNNING",List.of(),true)));
            when(chats.get("alice","assistant",chat.id())).thenReturn(chat);
            when(tx.execute(any())).thenAnswer(inv->((TransactionCallback<?>)inv.getArgument(0)).doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));
            when(jdbc.queryForList(contains("FROM rag.mcp_calls"),eq(UUID.class),eq("alice"),eq(request),eq("project-files"),eq("write"),anyString())).thenReturn(List.of(callId));
            when(jdbc.queryForList(contains("FROM rag.mcp_tool_permissions"),eq(String.class),eq("alice"),eq(chat.id()),eq("project-files"),eq("write"),eq(signature)))
                    .thenReturn(granted ? List.of("write") : List.of());
            when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
            doReturn(List.of(new McpApprovalService.Call(callId,"project-files","write",input,status,1,"cached",signature))).when(service).calls("alice",request);
        }
        ToolCallback guarded() { return service.guard("project-files",delegate,context); }
    }
}
