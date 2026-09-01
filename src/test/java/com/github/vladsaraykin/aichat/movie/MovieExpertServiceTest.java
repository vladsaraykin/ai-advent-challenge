package com.github.vladsaraykin.aichat.movie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.vladsaraykin.aichat.config.ChatProperties;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

class MovieExpertServiceTest {

    @Test
    void sendsSameQuestionWithoutAndWithControls() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenReturn(response("free answer"), response("controlled answer"));
        var properties = new ChatProperties(Path.of("data/chat.json"), "https://example.test/v1", false);
        var service = new MovieExpertService(model, properties);
        var form = new MovieComparisonForm();
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

        MovieComparisonResult result = service.compare(form);

        assertThat(result).isEqualTo(new MovieComparisonResult("free answer", "controlled answer"));
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, org.mockito.Mockito.times(2)).call(prompts.capture());
        List<Prompt> requests = prompts.getAllValues();
        assertThat(requests)
                .allSatisfy(prompt -> assertThat(prompt.getInstructions().getLast().getText())
                        .isEqualTo("Посоветуй медленную фантастику"));

        OpenAiChatOptions unrestricted = (OpenAiChatOptions) requests.getFirst().getOptions();
        assertThat(unrestricted.getModel()).isEqualTo("gpt-4.1-mini");
        assertThat(unrestricted.getMaxTokens()).isNull();
        assertThat(unrestricted.getStop()).isNull();

        OpenAiChatOptions controlled = (OpenAiChatOptions) requests.getLast().getOptions();
        assertThat(controlled.getTemperature()).isEqualTo(0.3);
        assertThat(controlled.getTopP()).isEqualTo(0.8);
        assertThat(controlled.getMaxTokens()).isEqualTo(600);
        assertThat(controlled.getStop()).containsExactly("<DONE>");
        assertThat(controlled.getExtraBody()).containsEntry("top_k", 40);
        assertThat(controlled.getResponseFormat().getType())
                .isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA);
        assertThat(requests.getLast().getInstructions().getFirst().getText())
                .contains("не более 200 слов", "валидный JSON", "<DONE>");
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
