package com.github.vladsaraykin.aichat.config;

import com.github.vladsaraykin.aichat.history.ChatHistoryStore;
import com.github.vladsaraykin.aichat.history.JsonFileChatHistoryStore;
import java.net.InetSocketAddress;
import java.net.Proxy;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties({ChatProperties.class, OpenAiProxyProperties.class})
public class AppConfiguration {

    @Bean
    ChatHistoryStore chatHistoryStore(ObjectMapper objectMapper, ChatProperties properties) {
        return new JsonFileChatHistoryStore(objectMapper, properties.historyFile());
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.openai.proxy", name = "enabled", havingValue = "true")
    OpenAiHttpClientBuilderCustomizer openAiProxyCustomizer(OpenAiProxyProperties properties) {
        if (properties.host() == null || properties.host().isBlank()) {
            throw new IllegalArgumentException("OpenAI proxy host must not be blank");
        }
        if (properties.port() < 1 || properties.port() > 65_535) {
            throw new IllegalArgumentException("OpenAI proxy port must be between 1 and 65535");
        }

        var proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(properties.host(), properties.port()));
        return builder -> builder.proxy(proxy);
    }
}
