package com.github.vladsaraykin.aichat.review.domain;

import com.github.vladsaraykin.aichat.agent.domain.ChatMessage;
import com.github.vladsaraykin.aichat.rag.domain.RagQuestion;
import java.util.List;
import java.util.UUID;

public record CodeReview(UUID id, String repository, int pullNumber, String baseSha, String headSha,
                         List<Finding> findings, List<RagQuestion.Source> sources,
                         ChatMessage.Metrics metrics, boolean usageIncomplete, String markdown) {
    public record Finding(String category, String severity, String path, String side, int line,
                          String quote, String description, String recommendation, List<RagQuestion.Evidence> context) { }
}
