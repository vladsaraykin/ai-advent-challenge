package com.github.vladsaraykin.aichat.harness.infrastructure;

import com.github.vladsaraykin.aichat.agent.application.ChatFailure;
import com.github.vladsaraykin.aichat.agent.domain.ChatMessage;
import com.github.vladsaraykin.aichat.harness.domain.RequestContext;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class HarnessRequestRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    public HarnessRequestRepository(@Qualifier("ragJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record RequestView(RequestContext context,String status,List<ChatMessage.Metrics> usage,boolean usageComplete) { }
    public record UsageView(UUID requestId,String status,List<ChatMessage.Metrics> usage,boolean usageComplete) { }
    public List<UsageView> usage(String owner,UUID chat) {
        return jdbc.query("SELECT message_id,status,usage,usage_complete FROM rag.harness_requests WHERE owner_username=? AND chat_id=? ORDER BY updated_at",
                (rs,n)->new UsageView(rs.getObject(1,UUID.class),rs.getString(2),
                        json.readValue(rs.getString(3),json.getTypeFactory().constructCollectionType(List.class,ChatMessage.Metrics.class)),rs.getBoolean(4)),owner,chat);
    }
    public Optional<RequestView> find(String owner,UUID id) {
        return jdbc.query("SELECT payload,status,usage,usage_complete FROM rag.harness_requests WHERE owner_username=? AND message_id=?",
                (rs,n)->new RequestView(json.readValue(rs.getString(1),RequestContext.class),rs.getString(2),
                        json.readValue(rs.getString(3),json.getTypeFactory().constructCollectionType(List.class,ChatMessage.Metrics.class)),rs.getBoolean(4)),owner,id).stream().findFirst();
    }
    public Optional<RequestView> pending(String owner,UUID chat) {
        return jdbc.queryForList("SELECT message_id FROM rag.harness_requests WHERE owner_username=? AND chat_id=? ORDER BY updated_at DESC LIMIT 1",
                UUID.class,owner,chat).stream().findFirst().flatMap(id->find(owner,id))
                .filter(request->Set.of("AWAITING_APPROVAL","FAILED","INTERRUPTED","RUNNING").contains(request.status()));
    }
    public void create(RequestContext context) {
        try { jdbc.update("INSERT INTO rag.harness_requests(owner_username,chat_id,message_id,payload) VALUES(?,?,?,?::jsonb)",
                context.owner(),context.chatId(),context.messageId(),json.writeValueAsString(context)); }
        catch(DuplicateKeyException error) { throw new ChatFailure(ChatFailure.Kind.BUSY,"В этом чате уже есть незавершённый запрос. Продолжите или отмените его."); }
    }
    public void context(RequestContext context) {
        jdbc.update("UPDATE rag.harness_requests SET payload=?::jsonb,updated_at=now() WHERE owner_username=? AND message_id=?",
                json.writeValueAsString(context),context.owner(),context.messageId());
    }
    public void status(String owner,UUID id,String status) {
        jdbc.update("UPDATE rag.harness_requests SET status=?,updated_at=now() WHERE owner_username=? AND message_id=?",status,owner,id);
    }
    public boolean claim(String owner,UUID id) {
        return jdbc.update("UPDATE rag.harness_requests SET status='RUNNING',updated_at=now() WHERE owner_username=? AND message_id=? AND status IN ('AWAITING_APPROVAL','FAILED','INTERRUPTED')",owner,id)==1;
    }
    public void usage(RequestContext context,ChatMessage.Metrics metrics) {
        if(context!=null && metrics!=null) jdbc.update("UPDATE rag.harness_requests SET usage=usage || ?::jsonb,usage_complete=usage_complete AND ? WHERE owner_username=? AND message_id=?",
                json.writeValueAsString(List.of(metrics)),!Boolean.FALSE.equals(metrics.usageAvailable()),context.owner(),context.messageId());
    }
    public void incomplete(RequestContext context) {
        jdbc.update("UPDATE rag.harness_requests SET usage_complete=false WHERE owner_username=? AND message_id=?",context.owner(),context.messageId());
    }
    /** Only one backend instance is supported; a started external write is never automatically retried. */
    @jakarta.annotation.PostConstruct public void recover() {
        jdbc.update("UPDATE rag.harness_requests SET status='INTERRUPTED',usage_complete=false WHERE status='RUNNING'");
        jdbc.update("UPDATE rag.mcp_calls SET status='UNCERTAIN' WHERE status='RUNNING'");
    }
}
