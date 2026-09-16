package com.github.vladsaraykin.aichat.user.domain;

import java.time.Instant;
import java.util.List;

public record UserProfile(String username, String displayName, String responseStyle,
                          String responseFormat, List<String> constraints,
                          long version, Instant updatedAt) {
    public UserProfile {
        username = normalized(username, 40, "username");
        displayName = normalized(displayName, 80, "displayName");
        responseStyle = normalized(responseStyle, 500, "responseStyle");
        responseFormat = normalized(responseFormat, 500, "responseFormat");
        constraints = constraints == null ? List.of() : constraints.stream()
                .map(value -> normalized(value, 300, "constraint")).filter(value -> !value.isBlank()).distinct().toList();
        if (displayName.isBlank() || responseStyle.isBlank() || responseFormat.isBlank()
                || constraints.size() > 20 || version < 0 || updatedAt == null) throw new IllegalArgumentException("Invalid profile");
    }
    public static UserProfile initial(String username) {
        return new UserProfile(username, username, "Кратко и по существу",
                "Структурированный Markdown", List.of(), 0, Instant.now());
    }
    private static String normalized(String value, int max, String field) {
        if (value == null) return "";
        String result = value.strip().replaceAll("(?U)\\s+", " ");
        if (result.length() > max) throw new IllegalArgumentException(field + " is too long");
        return result;
    }
}
