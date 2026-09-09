package com.github.vladsaraykin.aichat.agent.infrastructure;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenAiConversationModelTest {
    private static final TokenPricing PRICING = new TokenPricing(
            new BigDecimal("0.40"), new BigDecimal("0.10"), new BigDecimal("1.60"));
    private final AgentDefinition definition = new AgentDefinition("architect", "Architect", "Architecture",
            "gpt-4.1-mini", "System instruction", 4096, null, 60, 60000, PRICING);
    private static final TokenCounter CHARACTER_COUNTER = (model, text) -> text.length();
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
        var client = new OpenAiConversationModel(model, CHARACTER_COUNTER);
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
        var reply = new OpenAiConversationModel(model, CHARACTER_COUNTER).reply(definition, List.of());
        assertThat(reply.text()).isEqualTo("Частичный ответ");
        assertThat(reply.metrics().finishReason()).isEqualTo("length");
    }

    @Test void recordsRequestHistoryCacheTokensAndCalculatedCost() {
        ChatModel model = mock(ChatModel.class);
        var generation = new org.springframework.ai.chat.model.Generation(
                new org.springframework.ai.chat.messages.AssistantMessage("Готово"),
                org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("STOP").build());
        var usage = new DefaultUsage(1000, 200, 1200, null, 400L, null);
        when(model.call(any(Prompt.class))).thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                List.of(generation), ChatResponseMetadata.builder().usage(usage).build()));
        var messages = List.of(
                new ChatMessage(UUID.randomUUID(), ChatMessage.Role.USER, "old", Instant.now(), null),
                new ChatMessage(UUID.randomUUID(), ChatMessage.Role.ASSISTANT, "answer", Instant.now(), null),
                new ChatMessage(UUID.randomUUID(), ChatMessage.Role.USER, "new question", Instant.now(), null));

        var metrics = new OpenAiConversationModel(model, CHARACTER_COUNTER).reply(definition, messages).metrics();

        assertThat(metrics.currentMessageTokens()).isEqualTo(12);
        assertThat(metrics.historyTokens()).isEqualTo(9);
        assertThat(metrics.systemPromptTokens()).isEqualTo(18);
        assertThat(metrics.promptTokens()).isEqualTo(1000);
        assertThat(metrics.cachedPromptTokens()).isEqualTo(400);
        assertThat(metrics.completionTokens()).isEqualTo(200);
        assertThat(metrics.totalTokens()).isEqualTo(1200);
        assertThat(metrics.inputCostUsd()).isEqualByComparingTo("0.00028000");
        assertThat(metrics.outputCostUsd()).isEqualByComparingTo("0.00032000");
        assertThat(metrics.totalCostUsd()).isEqualByComparingTo("0.00060000");
    }
}
