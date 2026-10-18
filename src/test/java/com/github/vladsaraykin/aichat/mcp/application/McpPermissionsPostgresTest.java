package com.github.vladsaraykin.aichat.mcp.application;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.harness.domain.*;
import com.github.vladsaraykin.aichat.harness.infrastructure.HarnessRequestRepository;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A fresh disposable DB only. Refuses any database that already contains the rag schema. */
@EnabledIfEnvironmentVariable(named="MCP_PERMISSION_TEST_DATABASE_URL",matches=".+")
class McpPermissionsPostgresTest {
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    String owner="permission_test_"+UUID.randomUUID();
    UUID chatId=UUID.randomUUID();
    final AtomicInteger executions=new AtomicInteger();
    ChatRepository chats;
    HarnessRequestRepository requests;
    TransactionTemplate tx;
    McpApprovalService approvals;

    @BeforeAll static void schema() {
        ds=new DriverManagerDataSource(System.getenv("MCP_PERMISSION_TEST_DATABASE_URL"),
                System.getenv().getOrDefault("MCP_PERMISSION_TEST_DATABASE_USERNAME",System.getenv("USER")),
                System.getenv().getOrDefault("MCP_PERMISSION_TEST_DATABASE_PASSWORD",""));
        jdbc=new JdbcTemplate(ds);
        if(jdbc.queryForObject("SELECT to_regnamespace('rag') IS NOT NULL",Boolean.class))
            throw new IllegalStateException("Refusing existing rag schema: disposable database required");
        jdbc.execute("CREATE SCHEMA rag");
        jdbc.execute("CREATE TABLE rag.chats(owner_username text NOT NULL,id uuid NOT NULL,PRIMARY KEY(owner_username,id))");
        new ResourceDatabasePopulator(new ClassPathResource("db/rag/migration/V4__harness_requests.sql"),
                new ClassPathResource("db/rag/migration/V8__mcp_tool_permissions.sql")).execute(ds);
    }
    @BeforeEach void fixture() {
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        chats=mock(ChatRepository.class);
        var base=Chat.create("assistant");
        var task=MemoryService.update(base.workingMemory(),new MemoryService.TaskData("Docs",Map.of("format","Markdown"),Map.of(),Map.of(),List.of()),"",List.of(),null);
        when(chats.get(anyString(),eq("assistant"),any())).thenReturn(base.withWorkingMemory(MemoryService.advance(task)));
        requests=new HarnessRequestRepository(jdbc);approvals=new McpApprovalService(jdbc,tx,requests,chats);
    }
    @AfterEach void clean() { jdbc.update("DELETE FROM rag.chats WHERE owner_username LIKE ?",owner+"%"); }
    RequestContext request(String username,UUID chat) {
        jdbc.update("INSERT INTO rag.chats(owner_username,id) VALUES(?,?) ON CONFLICT DO NOTHING",username,chat);
        jdbc.update("UPDATE rag.harness_requests SET status='COMPLETED' WHERE owner_username=? AND chat_id=?",username,chat);
        var context=new RequestContext(username,"assistant",chat,UUID.randomUUID(),"/files Docs",
                new ChatSettings(0,false,List.of("project-files"),null),null,null,null);
        requests.create(context);return context;
    }
    ToolCallback delegate(String description) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("write").description(description).inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String input) { executions.incrementAndGet();return "saved"; }
        };
    }
    McpApprovalService.Call grant(RequestContext context) {
        var guarded=approvals.guard("project-files",delegate("Write"),context);
        assertThatThrownBy(()->guarded.call("{\"path\":\"a.md\"}")).isInstanceOf(McpApprovalService.Required.class);
        var call=approvals.calls(owner,context.messageId()).getFirst();
        approvals.decide(owner,context.messageId(),call.id(),true,true);
        guarded.call("{\"path\":\"a.md\"}");return call;
    }
    @Test void permissionSurvivesRestartAllowsOtherArgumentsAndCanBeRevoked() {
        grant(request(owner,chatId));
        var restarted=new McpApprovalService(jdbc,tx,requests,chats);
        assertThat(restarted.permissions(owner,chatId)).hasSize(1);
        var next=request(owner,chatId);
        var guard=restarted.guard("project-files",delegate("Write"),next);
        assertThat(guard.call("{\"path\":\"b.md\"}")).contains("saved");
        guard.call("{\"path\":\"b.md\"}");assertThat(executions).hasValue(2);
        restarted.revoke(owner,chatId,"project-files","write");
        assertThat(restarted.permissions(owner,chatId)).isEmpty();
        var after=request(owner,chatId);
        assertThatThrownBy(()->restarted.guard("project-files",delegate("Write"),after).call("{}"))
                .isInstanceOf(McpApprovalService.Required.class);
    }
    @Test void longTaskContinuesPast24AndReplaysCachedCallsEvenWhenDistinctBudgetIsExhausted() {
        var context=request(owner,chatId);
        grant(context);
        var bounded=new McpApprovalService(jdbc,tx,requests,chats,new McpCallBudget(32,128));
        var guard=bounded.guard("project-files",delegate("Write"),context);
        for(int i=1;i<32;i++) assertThat(guard.call("{\"page\":"+i+"}")).contains("saved");
        assertThat(executions).hasValue(32);
        assertThat(bounded.calls(owner,context.messageId())).hasSize(32);
        assertThat(guard.call("{\"page\":32}")).contains("Инструмент НЕ вызван", "незавершённое");
        assertThat(executions).hasValue(32);
        var restarted=new McpApprovalService(jdbc,tx,requests,chats,new McpCallBudget(32,128));
        assertThat(restarted.guard("project-files",delegate("Write"),context).call("{\"page\":31}"))
                .contains("saved");
        assertThat(executions).hasValue(32);
        var increased=new McpApprovalService(jdbc,tx,requests,chats,new McpCallBudget(64,128));
        assertThat(increased.guard("project-files",delegate("Write"),context).call("{\"page\":32}"))
                .contains("saved");
        assertThat(executions).hasValue(33);
    }
    @Test void permissionIsIsolatedByOwnerChatToolSignatureAndDoesNotRepeatUncertainWrites() {
        grant(request(owner,chatId));
        for(var context:List.of(request(owner,UUID.randomUUID()),request(owner+"_other",chatId))) {
            assertThatThrownBy(()->approvals.guard("project-files",delegate("Write"),context).call("{}"))
                    .isInstanceOf(McpApprovalService.Required.class);
        }
        assertThatThrownBy(()->approvals.guard("project-files",delegate("Changed"),request(owner,chatId)).call("{}"))
                .isInstanceOf(McpApprovalService.Required.class);
        var unknown=request(owner,chatId);var guard=approvals.guard("project-files",delegate("Write"),unknown);
        guard.call("{}");var call=approvals.calls(owner,unknown.messageId()).getFirst();
        jdbc.update("UPDATE rag.mcp_calls SET status='UNCERTAIN' WHERE id=?",call.id());
        int count=executions.get();
        assertThatThrownBy(()->guard.call("{}")).isInstanceOf(ChatFailure.class).hasMessageContaining("автоматический повтор запрещён");
        assertThat(executions).hasValue(count);
    }
    @Test void grantAndCurrentApprovalAreAtomicAndCannotBeIssuedByAnotherOwner() {
        var context=request(owner,chatId);var guard=approvals.guard("project-files",delegate("Write"),context);
        assertThatThrownBy(()->guard.call("{}")).isInstanceOf(McpApprovalService.Required.class);
        var call=approvals.calls(owner,context.messageId()).getFirst();
        assertThatThrownBy(()->approvals.decide(owner+"_other",context.messageId(),call.id(),true,true)).isInstanceOf(ChatFailure.class);
        var failing=spy(jdbc);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture"))
                .when(failing).update(contains("INSERT INTO rag.mcp_tool_permissions"),any(Object[].class));
        var broken=new McpApprovalService(failing,tx,requests,chats);
        assertThatThrownBy(()->broken.decide(owner,context.messageId(),call.id(),true,true)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(approvals.calls(owner,context.messageId()).getFirst().status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(approvals.permissions(owner,chatId)).isEmpty();
    }
}
