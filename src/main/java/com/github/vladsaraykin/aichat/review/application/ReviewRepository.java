package com.github.vladsaraykin.aichat.review.application;

import com.github.vladsaraykin.aichat.agent.domain.ChatMessage;
import com.github.vladsaraykin.aichat.rag.domain.RagQuestion;
import com.github.vladsaraykin.aichat.review.domain.*;
import java.util.*;

public interface ReviewRepository {
    record Entry(String payloadSha, String status, CodeReview result) { }
    boolean begin(String owner, ReviewRequest request, String payloadSha);
    Optional<Entry> find(String owner, UUID id);
    void sources(String owner, UUID id, List<RagQuestion.Source> sources);
    void usage(String owner, UUID id, ChatMessage.Metrics metrics);
    void complete(String owner, CodeReview result);
    void fail(String owner, UUID id, String code);
}
