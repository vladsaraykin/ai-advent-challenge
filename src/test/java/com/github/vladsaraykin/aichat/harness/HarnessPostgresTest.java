package com.github.vladsaraykin.aichat.harness;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.agent.infrastructure.*;
import com.github.vladsaraykin.aichat.harness.domain.*;
import com.github.vladsaraykin.aichat.harness.infrastructure.*;
import com.github.vladsaraykin.aichat.mcp.application.McpApprovalService;
import com.github.vladsaraykin.aichat.rag.infrastructure.RagConfiguration;
import com.github.vladsaraykin.aichat.user.domain.*;
import com.github.vladsaraykin.aichat.user.infrastructure.JdbcUserRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Opt-in only: point RAG_TEST_DATABASE_* at a disposable database, never production. */
@EnabledIfEnvironmentVariable(named="RAG_TEST_DATABASE_URL", matches=".+")
class HarnessPostgresTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private JdbcChatRepository chats;
    private JdbcUserRepository users;
    private String owner;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(System.getenv("RAG_TEST_DATABASE_URL"),
                System.getenv("RAG_TEST_DATABASE_USERNAME"), System.getenv("RAG_TEST_DATABASE_PASSWORD"));
        org.flywaydb.core.Flyway.configure().dataSource(ds).schemas("rag").defaultSchema("rag")
                .locations("classpath:db/rag/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        chats = new JdbcChatRepository(jdbc, tx);
        users = new JdbcUserRepository(jdbc);
        owner = "day25_" + UUID.randomUUID().toString().substring(0, 8);
        users.create(new UserAccount(owner,"bcrypt-test-hash",Instant.now()),UserProfile.initial(owner));
    }
    @AfterEach void clean() {
        // Deletes only this test's randomly generated owner; never clears the schema.
        jdbc.update("DELETE FROM rag.chats WHERE owner_username=?",owner);
        jdbc.update("DELETE FROM rag.long_term_memory WHERE owner_username=?",owner);
        jdbc.update("DELETE FROM rag.users WHERE username=?",owner);
    }

    @Test void completeHistorySurvivesRetentionAndBranchesAreAtomicAndOwnerScoped() {
        var chat=Chat.create("architect",ContextStrategyType.BRANCHING);
        for(int i=0;i<12;i++) chat=chat.append(message(ChatMessage.Role.USER,"Вопрос "+i),message(ChatMessage.Role.ASSISTANT,"Ответ "+i));
        var task=MemoryService.update(chat.workingMemory(),new MemoryService.TaskData("Цель",
                Map.of("r","Требование"),Map.of("c","Ограничение"),Map.of(),List.of(),Map.of("RAG","Поиск контекста")),"",List.of(),null);
        chat=chat.withWorkingMemory(task);
        chats.save(owner,chat);
        chats.save(owner,chat.window(4));
        assertThat(chats.get(owner,"architect",chat.id()).messages()).hasSize(4);
        assertThat(chats.history(owner,"architect",chat.id())).hasSize(24);
        assertThatThrownBy(()->chats.history("other","architect",chats.list(owner,"architect").getFirst().id())).isInstanceOf(ChatFailure.class);
        var checkpoint=chats.get(owner,"architect",chat.id()).fork("A","B");
        chats.save(owner,checkpoint);
        var restarted=new JdbcChatRepository(jdbc,tx).get(owner,"architect",chat.id());
        assertThat(restarted.readOnly()).isTrue();
        assertThat(restarted.branches()).hasSize(2);
        assertThat(restarted.branches().getFirst().workingMemory().terms()).containsEntry("RAG","Поиск контекста");
        var child=restarted.branches().getFirst();
        assertThat(chats.history(owner,"architect",child.id())).hasSize(24);
        chats.delete(owner,"architect",child.id());
        assertThat(chats.get(owner,"architect",chat.id()).branches()).hasSize(1);
        assertThat(chats.get(owner,"architect",chat.id()).readOnly()).isTrue();
        assertThat(jdbc.queryForObject("SELECT working_memory->>'goal' FROM rag.chats WHERE owner_username=? AND id=?",String.class,owner,chat.id())).isEqualTo("Цель");
    }

    @Test void settingsAndLongTermMemoryUseOptimisticVersionsAndSurviveRestart() {
        var chat=Chat.create("chef");chats.save(owner,chat);
        var settings=new ChatSettingsRepository(jdbc,chats);
        var saved=settings.save(owner,"chef",chat.id(),new ChatSettings(0,true,List.of("files"),null,ChatSettings.LlmProvider.LOCAL_MLX));
        assertThat(new ChatSettingsRepository(jdbc,chats).get(owner,"chef",chat.id())).isEqualTo(saved);
        assertThatThrownBy(()->settings.save(owner,"chef",chat.id(),ChatSettings.defaults())).isInstanceOf(ChatFailure.class);
        var memory=new JdbcLongTermMemoryRepository(jdbc,tx);
        var entry=new LongTermMemory.Entry(UUID.randomUUID(),WorkingMemory.Scope.GLOBAL,"","style","Кратко",chat.id(),UUID.randomUUID(),Instant.now());
        memory.put(owner,"chef",0,entry);
        assertThat(new JdbcLongTermMemoryRepository(jdbc,tx).get(owner,"chef").entries()).containsExactly(entry);
        assertThat(memory.get(owner,"architect").entries()).isEmpty();
        assertThatThrownBy(()->memory.put(owner,"chef",0,entry)).isInstanceOf(ChatFailure.class);
        memory.delete(owner,"chef",1,entry.id());
        assertThat(memory.get(owner,"chef").resolvedProposals()).contains(entry.id());
    }

    @Test void toolsRequireExactConfirmationCacheSuccessfulWritesAndNeverRepeatUncertainWrites() {
        var chat=Chat.create("assistant");
        var task=MemoryService.update(chat.workingMemory(),new MemoryService.TaskData("Сохранить отчёт",
                Map.of("format","Текст"),Map.of(),Map.of(),List.of()),"",List.of(),null);
        chat=chat.withWorkingMemory(MemoryService.advance(task));chats.save(owner,chat);
        var requests=new HarnessRequestRepository(jdbc);
        var context=new RequestContext(owner,"assistant",chat.id(),UUID.randomUUID(),"Сохрани",new ChatSettings(0,false,List.of("files"),null),null,null,null);
        requests.create(context);
        UUID activeChat = chat.id();
        assertThatThrownBy(() -> chats.delete(owner, "assistant", activeChat))
                .isInstanceOf(ChatFailure.class).hasMessageContaining("выполняется запрос");
        assertThat(chats.get(owner, "assistant", activeChat).id()).isEqualTo(activeChat);
        var approvals=new McpApprovalService(jdbc,tx,requests,chats);
        var count=new AtomicInteger();
        ToolCallback callback=new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("save").description("Save report").inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String input) { count.incrementAndGet();return "Отчёт сохранён успешно"; }
        };
        var tool=approvals.guard("files",callback,context);
        assertThatThrownBy(()->tool.call("{\"path\":\"report.txt\"}")).isInstanceOf(McpApprovalService.Required.class);
        assertThat(count).hasValue(0);
        var call=approvals.calls(owner,context.messageId()).getFirst();
        assertThatThrownBy(()->approvals.decide("other",context.messageId(),call.id(),true)).isInstanceOf(ChatFailure.class);
        approvals.decide(owner,context.messageId(),call.id(),true);
        assertThat(tool.call("{ \"path\": \"report.txt\" }")).contains("Отчёт сохранён успешно");
        tool.call("{\"path\":\"report.txt\"}");
        assertThat(count).hasValue(1);
        assertThat(approvals.sources(context)).hasSize(1);
        assertThatThrownBy(()->tool.call("{\"path\":\"other.txt\"}")).isInstanceOf(McpApprovalService.Required.class);
        assertThat(count).hasValue(1);
        var other=approvals.calls(owner,context.messageId()).getLast();
        approvals.decide(owner,context.messageId(),other.id(),false);
        assertThat(tool.call("{\"path\":\"other.txt\"}")).contains("отклонил");
        jdbc.update("UPDATE rag.mcp_calls SET status='UNCERTAIN' WHERE id=?",call.id());
        assertThatThrownBy(()->tool.call("{\"path\":\"report.txt\"}")).hasMessageContaining("автоматический повтор запрещён");
        assertThat(count).hasValue(1);
        assertThat(requests.find("other",context.messageId())).isEmpty();
    }
    private ChatMessage message(ChatMessage.Role role,String text) { return new ChatMessage(UUID.randomUUID(),role,text,Instant.now(),null); }
}
