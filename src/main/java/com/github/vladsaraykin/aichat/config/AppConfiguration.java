package com.github.vladsaraykin.aichat.config;

import com.github.vladsaraykin.aichat.history.ChatHistoryStore;
import com.github.vladsaraykin.aichat.history.JsonFileChatHistoryStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(ChatProperties.class)
public class AppConfiguration {

    @Bean
    ChatHistoryStore chatHistoryStore(ObjectMapper objectMapper, ChatProperties properties) {
        return new JsonFileChatHistoryStore(objectMapper, properties.historyFile());
    }
}
