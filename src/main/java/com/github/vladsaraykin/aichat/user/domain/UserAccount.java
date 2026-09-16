package com.github.vladsaraykin.aichat.user.domain;

import java.time.Instant;

public record UserAccount(String username, String passwordHash, Instant createdAt) { }
