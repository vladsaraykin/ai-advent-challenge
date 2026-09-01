package com.github.vladsaraykin.aichat.movie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.vladsaraykin.aichat.config.ChatProperties;
import com.github.vladsaraykin.aichat.history.ChatMessage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

class MovieExpertServiceTest {

    @Test
    void defaultModeContinuesConversationWithoutControls() {
        ChatModel model = modelReturning("answer");
        MovieExpertService service = service(model);
        MovieComparisonForm form = new MovieComparisonForm();
        form.setMode(MovieChatMode.DEFAULT);
        form.setUserPrompt("Следующий вопрос");
        List<ChatMessage> history = List.of(
                new ChatMessage(ChatMessage.Role.USER, "Первый вопрос", Instant.now()),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "Первый ответ", Instant.now()));

        assertThat(service.send(form, history)).isEqualTo("answer");

        Prompt prompt = capturedPrompt(model);
        assertThat(prompt.getInstructions())
                .extracting(message -> message.getMessageType(), message -> message.getText())
                .containsSequence(
                        org.assertj.core.groups.Tuple.tuple(MessageType.USER, "Первый вопрос"),
                        org.assertj.core.groups.Tuple.tuple(MessageType.ASSISTANT, "Первый ответ"),
                        org.assertj.core.groups.Tuple.tuple(MessageType.USER, "Следующий вопрос"));
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
        assertThat(options.getMaxTokens()).isNull();
        assertThat(options.getStop()).isNull();
        assertThat(options.getTemperature()).isNull();
        assertThat(prompt.getInstructions().getFirst().getText())
                .contains("только в сфере рекомендаций фильмов и сериалов", "Не поддерживай разговоры на посторонние темы");
    }

    @Test
    void controlledModeContinuesItsConversationWithRequestedControls() {
        ChatModel model = modelReturning("controlled answer");
        MovieExpertService service = service(model);
        MovieComparisonForm form = new MovieComparisonForm();
        form.setMode(MovieChatMode.CONTROLLED);
        form.setUserPrompt("Посоветуй медленную фантастику");
        form.setResponseFormat(MovieResponseFormat.JSON);
        form.setStopSequence("<DONE>");
        form.setMaxWords(200);
        form.setMaxTokens(600);
        form.setTemperature(0.3);
        form.setTopP(0.8);
        form.setTopK(40);
        form.setSeed(42);
        form.setFrequencyPenalty(0.2);

        assertThat(service.send(form, List.of())).isEqualTo("controlled answer");

        Prompt prompt = capturedPrompt(model);
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
        assertThat(options.getTemperature()).isEqualTo(0.3);
        assertThat(options.getTopP()).isEqualTo(0.8);
        assertThat(options.getMaxTokens()).isEqualTo(600);
        assertThat(options.getStop()).containsExactly("<DONE>");
        assertThat(options.getExtraBody()).containsEntry("top_k", 40);
        assertThat(options.getResponseFormat().getType()).isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA);
        assertThat(prompt.getInstructions().getFirst().getText()).contains("не более 200 слов", "валидный JSON", "<DONE>");
    }

    private static MovieExpertService service(ChatModel model) {
        return new MovieExpertService(model,
                new ChatProperties(Path.of("data/chat.json"), "https://example.test/v1", false));
    }

    private static ChatModel modelReturning(String answer) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(answer)))));
        return model;
    }

    private static Prompt capturedPrompt(ChatModel model) {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(captor.capture());
        return captor.getValue();
    }
}
