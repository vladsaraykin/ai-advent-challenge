package com.github.vladsaraykin.aichat.mcp.application;

import com.github.vladsaraykin.aichat.agent.application.ChatFailure;
import com.github.vladsaraykin.aichat.harness.domain.RequestContext;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProjectWorkspaceStore {
    private final JdbcTemplate jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    public ProjectWorkspaceStore(@Qualifier("ragJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record View(UUID id,long revision,Map<String,Object> state) { }
    public Optional<View> find(String owner,String agent,UUID chat) {
        return jdbc.query("SELECT id,revision,state FROM rag.project_workspaces WHERE id=? AND owner_username=? AND agent_id=?",
                (rs,n)->new View(rs.getObject(1,UUID.class),rs.getLong(2),json.readValue(rs.getString(3),Map.class)),chat,owner,agent)
                .stream().findFirst();
    }
    public String bind(RequestContext context,String tool,String input) {
        jdbc.update("INSERT INTO rag.project_workspaces(id,owner_username,agent_id) VALUES(?,?,?) ON CONFLICT(id) DO NOTHING",
                context.chatId(),context.owner(),context.agentId());
        var workspace=find(context.owner(),context.agentId(),context.chatId()).orElseThrow(()->
                new ChatFailure(ChatFailure.Kind.INVALID,"Рабочая область не принадлежит этому профилю."));
        Map<String,Object> args=new TreeMap<>(json.readValue(input,Map.class));
        // Model-provided workspace and revision are never authoritative.
        args.put("workspaceId",context.chatId().toString());args.remove("workspaceRevision");
        if (!ProjectWorkspacePolicy.mutates(tool)) args.put("workspaceRevision",workspace.revision());
        return json.writeValueAsString(args);
    }
    public void record(RequestContext context,String tool,String raw) {
        var result=payload(json.readTree(raw),0);
        if(result==null) throw new IllegalStateException("Missing workspace result");
        if(result.path("operationRejected").asBoolean()) {
            jdbc.update("UPDATE rag.project_workspaces SET revision=revision+1,updated_at=now() WHERE id=? AND owner_username=? AND agent_id=?",
                    context.chatId(),context.owner(),context.agentId());
            return;
        }
        var state=result.get("workspaceState");
        if(!context.chatId().toString().equals(state.path("id").asText())) throw new IllegalStateException("Workspace mismatch");
        Map<String,Object> metadata=new LinkedHashMap<>(json.convertValue(state,Map.class));
        if(result.has("diff")) { metadata.put("diff",result.get("diff").asText());metadata.put("truncated",result.path("truncated").asBoolean()); }
        if(result.has("treeHash")) metadata.put("treeHash",result.get("treeHash").asText());
        if(result.has("currentTreeHash")) metadata.put("treeHash",result.get("currentTreeHash").asText());
        else if(ProjectWorkspacePolicy.mutates(tool)) { metadata.put("treeHash","");metadata.put("diff",""); }
        if(result.has("prUrl")) metadata.put("prUrl",result.get("prUrl").asText());
        if(result.has("check")) metadata.put("lastCheck",Map.of("check",result.get("check").asText(),
                "passed",result.path("passed").asBoolean(),"treeHash",result.path("treeHash").asText(),
                "durationMs",result.path("durationMs").asLong()));
        if(result.has("pushed")) metadata.put("pushedHeadSha",result.path("pushedHeadSha").asText());
        jdbc.update("UPDATE rag.project_workspaces SET state=state || ?::jsonb,revision=revision+?,updated_at=now() WHERE id=? AND owner_username=? AND agent_id=?",
                json.writeValueAsString(metadata),ProjectWorkspacePolicy.mutates(tool) ? 1 : 0,context.chatId(),context.owner(),context.agentId());
    }
    private JsonNode payload(JsonNode node,int depth) {
        if(node==null || depth>8) return null;
        if(node.isObject() && (node.has("workspaceState") || node.path("operationRejected").asBoolean())) return node;
        if(node.isString()) {
            try { return payload(json.readTree(node.asText()),depth+1); } catch(RuntimeException ignored) { return null; }
        }
        if(node.isContainer()) for(var child:node) { var found=payload(child,depth+1);if(found!=null)return found; }
        return null;
    }
}
