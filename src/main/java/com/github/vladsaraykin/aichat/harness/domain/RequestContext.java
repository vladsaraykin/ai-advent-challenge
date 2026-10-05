package com.github.vladsaraykin.aichat.harness.domain;

import java.util.*;
import com.github.vladsaraykin.aichat.rag.domain.RagQuestion;
import com.github.vladsaraykin.aichat.agent.domain.ChatMessage;

/** Immutable per-request snapshot. Null sources means ordinary chat, empty sources means strict refusal. */
public record RequestContext(String owner,String agentId,UUID chatId,UUID messageId,String content,
                             ChatSettings settings,List<RagQuestion.Source> sources,
                             RagQuestion.Retrieval retrieval,ChatMessage.Metrics rewriteUsage) {
    public RequestContext { sources=sources==null ? null : List.copyOf(sources); }
    public boolean ragEnabled() { return sources!=null; }
}
