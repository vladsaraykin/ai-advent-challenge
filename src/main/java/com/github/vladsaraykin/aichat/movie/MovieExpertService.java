package com.github.vladsaraykin.aichat.movie;

import com.github.vladsaraykin.aichat.config.ChatProperties;
import com.github.vladsaraykin.aichat.history.ChatMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class MovieExpertService {

    private static final Logger logger = LoggerFactory.getLogger(MovieExpertService.class);
    private static final String JSON_SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "summary": {"type": "string"},
                "recommendations": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "properties": {
                      "title": {"type": "string"},
                      "rating": {"type": "number", "minimum": 0, "maximum": 10},
                      "releaseYear": {"type": "string"},
                      "whyItFits": {"type": "string"},
                      "difference": {"type": "string"},
                      "mood": {"type": "string"}
                    },
                    "required": ["title", "rating", "releaseYear", "whyItFits", "difference", "mood"]
                  }
                }
              },
              "required": ["summary", "recommendations"]
            }
            """;

    private final ChatModel chatModel;
    private final ChatProperties properties;
    private final String systemPrompt;

    public MovieExpertService(ChatModel chatModel, ChatProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.systemPrompt = loadSystemPrompt();
    }

    public String send(MovieComparisonForm form, List<ChatMessage> history) {
        logger.info("Calling movie expert: baseUrl={}, model={}, mode={}, historyMessages={}, format={}, maxWords={}, maxTokens={}, "
                        + "temperature={}, topP={}, topK={}, seed={}, frequencyPenalty={}",
                properties.providerBaseUrl(), form.getModel(), form.getMode(), history.size(), form.getResponseFormat(),
                form.getMaxWords(), form.getMaxTokens(), form.getTemperature(), form.getTopP(), form.getTopK(),
                form.getSeed(), form.getFrequencyPenalty());

        boolean controlled = form.getMode() == MovieChatMode.CONTROLLED;
        String prompt = controlled ? controlledSystemPrompt(form) : systemPrompt;
        OpenAiChatOptions options = controlled
                ? controlledOptions(form)
                : OpenAiChatOptions.builder().model(form.getModel()).build();
        return call(prompt, form.getUserPrompt(), history, options);
    }

    String controlledSystemPrompt(MovieComparisonForm form) {
        String formatInstruction = form.getResponseFormat() == MovieResponseFormat.JSON
                ? "Верни только валидный JSON по заданной API-схеме, без Markdown-ограждений и текста вне JSON."
                : "Ответь в Markdown. Начни с строки **Если выбирать один — [название].** Затем дай нумерованный список рекомендаций; для каждой укажи название, год или период, рейтинг 0–10, причину, отличие и настроение.";
        return systemPrompt + "\n\n# Обязательный контроль ответа\n"
                + formatInstruction + "\n"
                + "Ответ должен содержать не более " + form.getMaxWords() + " слов. "
                + "Заверши полный ответ маркером " + form.getStopSequence() + ". Не пиши ничего после него.";
    }

    private OpenAiChatOptions controlledOptions(MovieComparisonForm form) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(form.getModel())
                .temperature(form.getTemperature())
                .topP(form.getTopP())
                .maxTokens(form.getMaxTokens())
                .frequencyPenalty(form.getFrequencyPenalty())
                .seed(form.getSeed())
                .stop(List.of(form.getStopSequence()));

        if (form.getTopK() != null) {
            Map<String, Object> extraBody = new HashMap<>();
            extraBody.put("top_k", form.getTopK());
            builder.extraBody(extraBody);
        }
        if (form.getResponseFormat() == MovieResponseFormat.JSON) {
            OpenAiChatModel.ResponseFormat responseFormat = new OpenAiChatModel.ResponseFormat();
            responseFormat.setType(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA);
            responseFormat.setJsonSchema(JSON_SCHEMA);
            responseFormat.setStrict(true);
            builder.responseFormat(responseFormat);
        }
        return builder.build();
    }

    private String call(String system, String user, List<ChatMessage> history, OpenAiChatOptions options) {
        List<Message> messages = new java.util.ArrayList<>();
        messages.add(new SystemMessage(system));
        history.forEach(message -> messages.add(switch (message.role()) {
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> new AssistantMessage(message.content());
        }));
        messages.add(new UserMessage(user));
        return chatModel.call(new Prompt(messages, options))
                .getResult().getOutput().getText();
    }

    private static String loadSystemPrompt() {
        try {
            return new ClassPathResource("prompts/movie-expert.md")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load the movie expert system prompt", exception);
        }
    }
}
