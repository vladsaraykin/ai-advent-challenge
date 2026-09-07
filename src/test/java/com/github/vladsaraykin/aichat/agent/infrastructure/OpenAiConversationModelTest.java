package com.github.vladsaraykin.aichat.agent.infrastructure;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenAiConversationModelTest {
    private final AgentDefinition definition = new AgentDefinition("architect", "Architect", "Architecture",
            "gpt-4.1-mini", "System instruction", 4096, null, 60, 60000);
    @Test void mapsSystemThenUserAndAssistantMessagesInOrder() {
        var history = List.of(
                new ChatMessage(UUID.randomUUID(), ChatMessage.Role.USER, "Первый", Instant.now(), null),
                new ChatMessage(UUID.randomUUID(), ChatMessage.Role.ASSISTANT, "Ответ", Instant.now(), null),
                new ChatMessage(UUID.randomUUID(), ChatMessage.Role.USER, "Второй", Instant.now(), null));
        Prompt prompt = OpenAiConversationModel.prompt(definition, history);
        assertThat(prompt.getInstructions()).extracting(Message::getText)
                .containsExactly("System instruction", "Первый", "Ответ", "Второй");
        assertThat(prompt.getInstructions()).extracting(message -> message.getMessageType().getValue())
                .containsExactly("system", "user", "assistant", "user");
        var options = (OpenAiChatOptions) prompt.getOptions();
        assertThat(options.getModel()).isEqualTo("gpt-4.1-mini");
        assertThat(options.getMaxCompletionTokens()).isEqualTo(4096);
        assertThat(options.getMaxTokens()).isNull();
        assertThat(options.getReasoningEffort()).isNull();
        assertThat(options.getMaxRetries()).isZero();
    }

    @Test void sanitizesUpstreamException() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("secret-api-key body"));
        var client = new OpenAiConversationModel(model);
        assertThatThrownBy(() -> client.reply(definition, List.of())).isInstanceOf(ChatFailure.class)
                .hasMessageNotContaining("secret-api-key").hasMessageContaining("OpenAI");
    }

    @Test void normalizesSdkFinishReasonForTruncatedAnswerWarning() {
        ChatModel model = mock(ChatModel.class);
        var generation = new org.springframework.ai.chat.model.Generation(
                new org.springframework.ai.chat.messages.AssistantMessage("Частичный ответ"),
                org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("LENGTH").build());
        when(model.call(any(Prompt.class))).thenReturn(
                new org.springframework.ai.chat.model.ChatResponse(List.of(generation)));
        var reply = new OpenAiConversationModel(model).reply(definition, List.of());
        assertThat(reply.text()).isEqualTo("Частичный ответ");
        assertThat(reply.metrics().finishReason()).isEqualTo("length");
    }
}
