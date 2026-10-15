package com.github.vladsaraykin.aichat.harness.domain;

import java.util.*;
import com.github.vladsaraykin.aichat.rag.domain.RagQuestion;
import com.github.vladsaraykin.aichat.agent.domain.ChatMessage;

/** Immutable per-request snapshot. Null sources means ordinary chat, empty sources means strict refusal. */
public record RequestContext(String owner,String agentId,UUID chatId,UUID messageId,String content,
                             ChatSettings settings,List<RagQuestion.Source> sources,
                             RagQuestion.Retrieval retrieval,ChatMessage.Metrics rewriteUsage,
                             boolean projectHelp,String question,boolean mcpOnly) {
    public RequestContext(String owner,String agentId,UUID chatId,UUID messageId,String content,
            ChatSettings settings,List<RagQuestion.Source> sources,RagQuestion.Retrieval retrieval,
            ChatMessage.Metrics rewriteUsage) {
        this(owner,agentId,chatId,messageId,content,settings,sources,retrieval,rewriteUsage,false,null,false);
    }
    public RequestContext(String owner,String agentId,UUID chatId,UUID messageId,String content,
            ChatSettings settings,List<RagQuestion.Source> sources,RagQuestion.Retrieval retrieval,
            ChatMessage.Metrics rewriteUsage,boolean projectHelp,String question) {
        this(owner,agentId,chatId,messageId,content,settings,sources,retrieval,rewriteUsage,projectHelp,question,false);
    }
    public RequestContext { sources=sources==null ? null : List.copyOf(sources); }
    public boolean ragEnabled() { return sources!=null; }
    public String promptQuestion() { return projectHelp ? question : content; }
}
