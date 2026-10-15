package com.github.vladsaraykin.aichat.harness.domain;

import java.util.List;
import com.github.vladsaraykin.aichat.rag.domain.RetrievalOptions;

public record ChatSettings(long version,boolean ragEnabled,List<String> mcpServerIds,RetrievalOptions retrieval,
                           LlmProvider provider, String model, String reasoningEffort) {
    /** LOCAL_MLX is retained only to deserialize old PostgreSQL settings and is normalized below. */
    public enum LlmProvider { OPENAI, LOCAL_MLX }
    public ChatSettings(long version, boolean ragEnabled, List<String> mcpServerIds, RetrievalOptions retrieval) {
        this(version, ragEnabled, mcpServerIds, retrieval, LlmProvider.OPENAI, null, null);
    }
    public ChatSettings(long version, boolean ragEnabled, List<String> mcpServerIds, RetrievalOptions retrieval,
                        LlmProvider provider) {
        this(version, ragEnabled, mcpServerIds, retrieval, provider, null, null);
    }
    public ChatSettings {
        provider=LlmProvider.OPENAI;
        if(version<0) throw new IllegalArgumentException("Invalid settings version");
        if(mcpServerIds!=null && mcpServerIds.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Invalid MCP selection");
        mcpServerIds=mcpServerIds==null ? List.of() : mcpServerIds.stream().map(String::strip).distinct().sorted().toList();
        if(mcpServerIds.size()>8 || mcpServerIds.stream().anyMatch(id->id.isBlank() || id.length()>100))
            throw new IllegalArgumentException("Invalid MCP selection");
        retrieval=retrieval==null ? new RetrievalOptions(20,5,.2) : retrieval;
        if (model != null && (model.isBlank() || model.length() > 100)) throw new IllegalArgumentException("Invalid model");
        if (reasoningEffort != null && !java.util.Set.of("none", "low", "medium", "high", "xhigh", "max").contains(reasoningEffort))
            throw new IllegalArgumentException("Invalid reasoning effort");
    }
    public static ChatSettings defaults() { return new ChatSettings(0,false,List.of(),null); }
}
