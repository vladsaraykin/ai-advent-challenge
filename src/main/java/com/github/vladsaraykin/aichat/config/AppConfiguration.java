package com.github.vladsaraykin.aichat.config;

import java.net.InetSocketAddress;
import java.net.Proxy;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(OpenAiProxyProperties.class)
public class AppConfiguration implements WebMvcConfigurer {
    private final long streamTimeoutMs;
    public AppConfiguration(@org.springframework.beans.factory.annotation.Value("${app.streaming.timeout-seconds:900}") int timeoutSeconds) {
        if(timeoutSeconds<30 || timeoutSeconds>3600) throw new IllegalArgumentException("Invalid SSE timeout");
        streamTimeoutMs=timeoutSeconds*1000L;
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskExecutor mvcStreamingExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("sse-stream-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcStreamingExecutor());
        configurer.setDefaultTimeout(streamTimeoutMs);
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
