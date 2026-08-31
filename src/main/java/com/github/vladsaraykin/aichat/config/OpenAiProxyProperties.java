package com.github.vladsaraykin.aichat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.openai.proxy")
public record OpenAiProxyProperties(boolean enabled, String host, int port) {
}
