package com.github.vladsaraykin.aichat.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.chat")
public record ChatProperties(Path historyFile, String providerBaseUrl, boolean logPrompts) {
}
