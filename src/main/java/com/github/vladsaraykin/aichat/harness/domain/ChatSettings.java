package com.github.vladsaraykin.aichat.harness.domain;

import java.util.List;
import com.github.vladsaraykin.aichat.rag.domain.RetrievalOptions;

public record ChatSettings(long version,boolean ragEnabled,List<String> mcpServerIds,RetrievalOptions retrieval,
                           LlmProvider provider) {
    public enum LlmProvider { OPENAI, LOCAL_MLX }
    public ChatSettings(long version, boolean ragEnabled, List<String> mcpServerIds, RetrievalOptions retrieval) {
        this(version, ragEnabled, mcpServerIds, retrieval, LlmProvider.OPENAI);
    }
    public ChatSettings {
        provider=provider==null ? LlmProvider.OPENAI : provider;
        if(version<0) throw new IllegalArgumentException("Invalid settings version");
        if(mcpServerIds!=null && mcpServerIds.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Invalid MCP selection");
        mcpServerIds=mcpServerIds==null ? List.of() : mcpServerIds.stream().map(String::strip).distinct().sorted().toList();
        if(mcpServerIds.size()>8 || mcpServerIds.stream().anyMatch(id->id.isBlank() || id.length()>100))
            throw new IllegalArgumentException("Invalid MCP selection");
        retrieval=retrieval==null ? new RetrievalOptions(20,5,.2) : retrieval;
    }
    public static ChatSettings defaults() { return new ChatSettings(0,false,List.of(),null); }
}
