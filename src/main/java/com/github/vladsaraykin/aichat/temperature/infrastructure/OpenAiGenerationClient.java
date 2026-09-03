package com.github.vladsaraykin.aichat.temperature.infrastructure;

import com.github.vladsaraykin.aichat.temperature.application.GenerationClient;
import com.github.vladsaraykin.aichat.temperature.application.GenerationCommand;
import com.github.vladsaraykin.aichat.temperature.application.ProviderGeneration;
import com.github.vladsaraykin.aichat.temperature.domain.TokenUsage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

@Component
public class OpenAiGenerationClient implements GenerationClient {
    private final ChatModel chatModel;

    public OpenAiGenerationClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public ProviderGeneration generate(GenerationCommand command) {
        OpenAiChatOptions options = optionsFor(command);
        ChatResponse response = chatModel.call(new Prompt(new UserMessage(command.prompt()), options));
        Usage usage = response.getMetadata().getUsage();
        return new ProviderGeneration(response.getResult().getOutput().getText(), usage(usage));
    }

    static OpenAiChatOptions optionsFor(GenerationCommand command) {
        return OpenAiChatOptions.builder()
                .model(command.model())
                .temperature(command.temperature())
                .maxTokens(command.maxTokens())
                .build();
    }

    private static TokenUsage usage(Usage usage) {
        return usage == null ? TokenUsage.EMPTY : new TokenUsage(value(usage.getPromptTokens()),
                value(usage.getCompletionTokens()), value(usage.getTotalTokens()));
    }

    private static int value(Integer value) { return value == null ? 0 : value; }
}
