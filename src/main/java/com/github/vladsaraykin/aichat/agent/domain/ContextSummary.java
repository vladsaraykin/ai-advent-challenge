package com.github.vladsaraykin.aichat.agent.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public record ContextSummary(String content, int summarizedMessages, Instant updatedAt,
                             int calls, int pricedCalls, long durationMs, int promptTokens,
                             int completionTokens, int totalTokens, BigDecimal totalCostUsd,
                             List<UserMessageFingerprint> userMessages, ArchivedUsage archivedUsage) {
    public ContextSummary(String content, int summarizedMessages, Instant updatedAt,
                          int calls, long durationMs, int promptTokens,
                          int completionTokens, int totalTokens, BigDecimal totalCostUsd) {
        this(content, summarizedMessages, updatedAt, calls, totalCostUsd == null ? 0 : calls, durationMs, promptTokens,
                completionTokens, totalTokens, totalCostUsd, List.of(), ArchivedUsage.empty());
    }

    public ContextSummary {
        if (content == null || content.isBlank() || summarizedMessages < 1 || updatedAt == null || calls < 1
                || pricedCalls < 0 || pricedCalls > calls) {
            throw new IllegalArgumentException("Invalid context summary");
        }
        userMessages = userMessages == null ? List.of() : List.copyOf(userMessages);
        archivedUsage = archivedUsage == null ? ArchivedUsage.empty() : archivedUsage;
    }

    public static ContextSummary updated(ContextSummary previous, String content, List<ChatMessage> messages,
                                         ChatMessage.Metrics metrics) {
        var ids = new ArrayList<UserMessageFingerprint>(previous == null ? List.of() : previous.userMessages());
        messages.stream().filter(message -> message.role() == ChatMessage.Role.USER)
                .map(message -> UserMessageFingerprint.from(message.id(), message.content())).forEach(ids::add);
        ArchivedUsage archived = ArchivedUsage.add(previous == null ? ArchivedUsage.empty()
                : previous.archivedUsage(), messages);
        return new ContextSummary(content, messages.size() + (previous == null ? 0 : previous.summarizedMessages()),
                Instant.now(), 1 + (previous == null ? 0 : previous.calls()),
                (metrics.totalCostUsd() == null ? 0 : 1) + (previous == null ? 0 : previous.pricedCalls()),
                metrics.durationMs() + (previous == null ? 0 : previous.durationMs()),
                metrics.promptTokens() + (previous == null ? 0 : previous.promptTokens()),
                metrics.completionTokens() + (previous == null ? 0 : previous.completionTokens()),
                metrics.totalTokens() + (previous == null ? 0 : previous.totalTokens()),
                add(previous == null ? null : previous.totalCostUsd(), metrics.totalCostUsd()), ids, archived);
    }

    public UserMessageMatch match(UUID messageId, String content) {
        return userMessages.stream().filter(message -> message.id().equals(messageId)).findFirst()
                .map(message -> message.contentSha256().equals(UserMessageFingerprint.hash(content))
                        ? UserMessageMatch.SAME : UserMessageMatch.CONFLICT)
                .orElse(UserMessageMatch.NONE);
    }

    public enum UserMessageMatch { NONE, SAME, CONFLICT }

    public record UserMessageFingerprint(UUID id, String contentSha256) {
        static UserMessageFingerprint from(UUID id, String content) {
            return new UserMessageFingerprint(id, hash(content));
        }

        static String hash(String content) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(content.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }
    }

    private static BigDecimal add(BigDecimal first, BigDecimal second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.add(second);
    }

    public record ArchivedUsage(int calls, int pricedCalls, int promptTokens,
                                int completionTokens, int totalTokens, BigDecimal totalCostUsd) {
        public ArchivedUsage {
            if (calls < 0 || pricedCalls < 0 || pricedCalls > calls || promptTokens < 0
                    || completionTokens < 0 || totalTokens < 0) {
                throw new IllegalArgumentException("Invalid archived usage");
            }
        }

        public static ArchivedUsage empty() { return new ArchivedUsage(0, 0, 0, 0, 0, null); }

        static ArchivedUsage add(ArchivedUsage previous, List<ChatMessage> messages) {
            int calls = previous.calls();
            int pricedCalls = previous.pricedCalls();
            int promptTokens = previous.promptTokens();
            int completionTokens = previous.completionTokens();
            int totalTokens = previous.totalTokens();
            BigDecimal totalCostUsd = previous.totalCostUsd();
            for (ChatMessage message : messages) {
                if (message.metrics() == null) continue;
                calls++;
                promptTokens += message.metrics().promptTokens();
                completionTokens += message.metrics().completionTokens();
                totalTokens += message.metrics().totalTokens();
                if (message.metrics().totalCostUsd() != null) {
                    pricedCalls++;
                    totalCostUsd = ContextSummary.add(totalCostUsd, message.metrics().totalCostUsd());
                }
            }
            return new ArchivedUsage(calls, pricedCalls, promptTokens, completionTokens,
                    totalTokens, totalCostUsd);
        }
    }
}
