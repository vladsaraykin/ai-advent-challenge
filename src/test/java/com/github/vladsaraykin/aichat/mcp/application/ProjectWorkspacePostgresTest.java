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
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** FRESH disposable database only; never accepts a database containing the rag schema. */
@EnabledIfEnvironmentVariable(named="PROJECT_WORKSPACE_TEST_DATABASE_URL",matches=".+")
class ProjectWorkspacePostgresTest {
    static JdbcTemplate jdbc;
    static DriverManagerDataSource ds;
    final JsonMapper json=JsonMapper.builder().build();
    ProjectWorkspaceStore store;
    McpApprovalService approvals;
    RequestContext context;
    @BeforeAll static void schema() {
        ds=new DriverManagerDataSource(System.getenv("PROJECT_WORKSPACE_TEST_DATABASE_URL"),
                System.getenv().getOrDefault("PROJECT_WORKSPACE_TEST_DATABASE_USERNAME",System.getenv("USER")),
                System.getenv().getOrDefault("PROJECT_WORKSPACE_TEST_DATABASE_PASSWORD",""));
        jdbc=new JdbcTemplate(ds);
        if(jdbc.queryForObject("SELECT to_regnamespace('rag') IS NOT NULL",Boolean.class)) throw new IllegalStateException("Disposable empty database required");
        jdbc.execute("CREATE SCHEMA rag");
        jdbc.execute("CREATE TABLE rag.chats(owner_username text NOT NULL,id uuid NOT NULL,PRIMARY KEY(owner_username,id))");
        new ResourceDatabasePopulator(new ClassPathResource("db/rag/migration/V4__harness_requests.sql"),
                new ClassPathResource("db/rag/migration/V8__mcp_tool_permissions.sql"),
                new ClassPathResource("db/rag/migration/V9__project_workspaces.sql")).execute(ds);
    }
    @BeforeEach void fixture() {
        var base=Chat.create("assistant");
        var task=MemoryService.update(base.workingMemory(),new MemoryService.TaskData("Code",Map.of("test","required"),Map.of(),Map.of(),List.of()),"",List.of(),null);
        var chats=mock(ChatRepository.class);
        when(chats.get("vlad","assistant",base.id())).thenReturn(base.withWorkingMemory(MemoryService.advance(task)));
        context=new RequestContext("vlad","assistant",base.id(),UUID.randomUUID(),"/files Change API",
                new ChatSettings(0,false,List.of("project-files","project-git"),null),null,null,null,false,null,false,true);
        jdbc.update("INSERT INTO rag.chats VALUES(?,?)","vlad",base.id());
        var requests=new HarnessRequestRepository(jdbc);requests.create(context);
        store=new ProjectWorkspaceStore(jdbc);
        approvals=new McpApprovalService(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),requests,chats,McpCallBudget.defaults(),null,store);
    }
    ToolCallback tool(String name,AtomicInteger calls,boolean fail) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name(name).description(name).inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String input) {
                calls.incrementAndGet();if(fail)throw new IllegalStateException("Unknown external write result");
                assertThat(json.readTree(input).path("workspaceId").asString()).isEqualTo(context.chatId().toString());
                return json.writeValueAsString(List.of(Map.of("type","text","text",json.writeValueAsString(Map.of("workspaceState",
                        Map.of("id",context.chatId().toString(),"branch","ai/task-"+context.chatId(),"headSha","a".repeat(40)))))));
            }
        };
    }
    McpApprovalService.Call approve(ToolCallback callback,String server,String input,boolean remember) {
        var guard=approvals.guard(server,callback,context);
        assertThatThrownBy(()->guard.call(input)).isInstanceOf(McpApprovalService.Required.class);
        var call=approvals.calls("vlad",context.messageId()).stream().filter(c->c.status().equals("AWAITING_APPROVAL")).reduce((a,b)->b).orElseThrow();
        approvals.decide("vlad",context.messageId(),call.id(),true,remember);return call;
    }
    @Test void revisionInvalidatesReadCacheAndRestartPreservesMetadataAndPermissions() {
        var reads=new AtomicInteger();var read=tool("project_read_files",reads,false);
        approve(read,"project-files","{\"paths\":[\"README.md\"],\"workspaceId\":\"forged\"}",true);
        var guard=approvals.guard("project-files",read,context);
        guard.call("{\"paths\":[\"README.md\"]}");guard.call("{\"paths\":[\"README.md\"]}");assertThat(reads).hasValue(1);
        var writes=new AtomicInteger();var write=tool("project_apply_edits",writes,false);
        approve(write,"project-files","{}",true);approvals.guard("project-files",write,context).call("{}");
        guard.call("{\"paths\":[\"README.md\"]}");assertThat(reads).hasValue(2);assertThat(writes).hasValue(1);
        assertThat(new ProjectWorkspaceStore(jdbc).find("vlad","assistant",context.chatId())).hasValueSatisfying(view->{
            assertThat(view.revision()).isEqualTo(1);assertThat(view.state().get("branch")).isEqualTo("ai/task-"+context.chatId());
        });
        assertThat(store.find("alice","assistant",context.chatId())).isEmpty();
    }
    @Test void savedPermissionCannotApprovePushAndUnknownWritesAreNeverReexecuted() {
        var calls=new AtomicInteger();var push=tool("project_git_push",calls,false);
        var pending=approve(push,"project-git","{\"expectedHeadSha\":\""+"a".repeat(40)+"\"}",false);
        var guard=approvals.guard("project-git",push,context);guard.call(pending.input());guard.call(pending.input());assertThat(calls).hasValue(1);
        jdbc.update("INSERT INTO rag.mcp_tool_permissions VALUES(?,?,?,?,?,now())","vlad",context.chatId(),"project-git","project_git_push",pending.toolSignature());
        String next="{\"expectedHeadSha\":\""+"b".repeat(40)+"\"}";
        assertThatThrownBy(()->guard.call(next)).isInstanceOf(McpApprovalService.Required.class);
        var awaiting=approvals.calls("vlad",context.messageId()).getLast();
        assertThatThrownBy(()->approvals.decide("vlad",context.messageId(),awaiting.id(),true,true)).hasMessageContaining("разового");
        assertThat(calls).hasValue(1);
        var writes=new AtomicInteger();var uncertain=tool("project_apply_edits",writes,true);
        approve(uncertain,"project-files","{}",true);
        var protectedWrite=approvals.guard("project-files",uncertain,context);
        assertThatThrownBy(()->protectedWrite.call("{}")).hasMessageContaining("автоматический повтор");
        assertThatThrownBy(()->protectedWrite.call("{}")).hasMessageContaining("автоматический повтор");assertThat(writes).hasValue(1);
    }
}
