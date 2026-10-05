package com.github.vladsaraykin.aichat.user.domain;

import java.time.Instant;

public record UserAccount(String username, String passwordHash, Instant createdAt) {
    public static void validateUsername(String username) {
        if (username == null || !username.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{2,39}")) {
            throw new com.github.vladsaraykin.aichat.agent.application.ChatFailure(
                    com.github.vladsaraykin.aichat.agent.application.ChatFailure.Kind.INVALID,
                    "Логин: 3–40 символов, латиница, цифры, точка, дефис или подчёркивание");
        }
    }
}
