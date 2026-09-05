package com.github.vladsaraykin.aichat.modelcomparison.infrastructure;

import com.github.vladsaraykin.aichat.modelcomparison.application.GenerationClient;
import com.github.vladsaraykin.aichat.modelcomparison.application.GenerationCommand;
import com.github.vladsaraykin.aichat.modelcomparison.application.ProviderGeneration;
import com.github.vladsaraykin.aichat.modelcomparison.domain.TokenUsage;
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
        ChatResponse response = chatModel.call(new Prompt(new UserMessage(command.prompt()), optionsFor(command)));
        Usage usage = response.getMetadata().getUsage();
        return new ProviderGeneration(response.getResult().getOutput().getText(), usage(usage));
    }

    static OpenAiChatOptions optionsFor(GenerationCommand command) {
        var builder = OpenAiChatOptions.builder().model(command.model())
                .maxCompletionTokens(command.maxTokens());
        if (command.model().startsWith("gpt-5")) {
            builder.reasoningEffort("low");
        }
        return builder.build();
    }

    private static TokenUsage usage(Usage usage) {
        return usage == null ? TokenUsage.EMPTY : new TokenUsage(value(usage.getPromptTokens()),
                value(usage.getCompletionTokens()), value(usage.getTotalTokens()));
    }

    private static int value(Integer value) { return value == null ? 0 : value; }
}
