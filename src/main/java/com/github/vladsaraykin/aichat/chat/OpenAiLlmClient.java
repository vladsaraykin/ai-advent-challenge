package com.github.vladsaraykin.aichat.chat;

import com.github.vladsaraykin.aichat.config.ChatProperties;
import com.github.vladsaraykin.aichat.history.ChatMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

@Component
public class OpenAiLlmClient implements LlmClient {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiLlmClient.class);

    private final ChatModel chatModel;
    private final ChatProperties properties;

    public OpenAiLlmClient(ChatModel chatModel, ChatProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    @Override
    public AiProvider provider() {
        return AiProvider.OPENAI;
    }

    @Override
    public String generate(ChatForm form, List<ChatMessage> history) {
        List<Message> requestMessages = new ArrayList<>();
        requestMessages.add(new SystemMessage(form.getSystemPrompt()));
        history.forEach(message -> requestMessages.add(toSpringMessage(message)));
        requestMessages.add(new UserMessage(form.getUserPrompt()));

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(form.getModel())
                .temperature(form.getTemperature())
                .topP(form.getTopP())
                .maxTokens(form.getMaxTokens())
                .frequencyPenalty(form.getFrequencyPenalty())
                .seed(form.getSeed());

        if (form.getTopK() != null) {
            Map<String, Object> extraBody = new HashMap<>();
            extraBody.put("top_k", form.getTopK());
            options.extraBody(extraBody);
        }

        logger.info("Calling OpenAI: baseUrl={}, model={}, historyMessages={}, temperature={}, topP={}, "
                        + "topK={}, maxTokens={}, seed={}, frequencyPenalty={}",
                properties.providerBaseUrl(), form.getModel(), history.size(), form.getTemperature(),
                form.getTopP(), form.getTopK(), form.getMaxTokens(), form.getSeed(), form.getFrequencyPenalty());
        logPrompts(form);

        try {
            return chatModel.call(new Prompt(requestMessages, options.build()))
                    .getResult()
                    .getOutput()
                    .getText();
        } catch (RuntimeException exception) {
            logger.error("OpenAI call failed: baseUrl={}, model={}",
                    properties.providerBaseUrl(), form.getModel(), exception);
            throw exception;
        }
    }

    private void logPrompts(ChatForm form) {
        if (properties.logPrompts()) {
            logger.info("OpenAI prompts: system={}, user={}", form.getSystemPrompt(), form.getUserPrompt());
        }
    }

    private static Message toSpringMessage(ChatMessage message) {
        return switch (message.role()) {
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> new AssistantMessage(message.content());
        };
    }
}
