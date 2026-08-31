package com.github.vladsaraykin.aichat.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.vladsaraykin.aichat.history.ChatConversation;
import com.github.vladsaraykin.aichat.config.ChatProperties;
import com.github.vladsaraykin.aichat.history.ChatHistoryStore;
import com.github.vladsaraykin.aichat.history.ChatMessage;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class ChatServiceTest {

    @Test
    void routesRequestToOpenAi() {
        ChatHistoryStore store = mock(ChatHistoryStore.class);
        LlmClient openAi = mock(LlmClient.class);
        when(openAi.provider()).thenReturn(AiProvider.OPENAI);
        when(store.load()).thenReturn(ChatConversation.empty());
        when(openAi.generate(any(ChatForm.class), eq(List.of()))).thenReturn("OpenAI answer");
        ChatForm form = new ChatForm();
        form.setUserPrompt("Hello");

        new ChatService(store, List.of(openAi)).send(form);

        verify(openAi).generate(eq(form), eq(List.of()));
        ArgumentCaptor<ChatConversation> saved = ArgumentCaptor.forClass(ChatConversation.class);
        verify(store).save(saved.capture());
        assertThat(saved.getValue().messages())
                .extracting(ChatMessage::content)
                .containsExactly("Hello", "OpenAI answer");
    }

    @Test
    void replaysHistoryPassesOptionsAndPersistsNewTurn() {
        ChatModel model = mock(ChatModel.class);
        ChatHistoryStore store = mock(ChatHistoryStore.class);
        var existingConversation = new ChatConversation(
                "Old system prompt",
                List.of(
                        new ChatMessage(ChatMessage.Role.USER, "Earlier question", Instant.now()),
                        new ChatMessage(ChatMessage.Role.ASSISTANT, "Earlier answer", Instant.now())),
                Instant.now());
        when(store.load()).thenReturn(existingConversation);
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("New answer")))));

        ChatForm form = new ChatForm();
        form.setModel("test-model");
        form.setSystemPrompt("Current system prompt");
        form.setUserPrompt("New question");
        form.setTemperature(0.4);
        form.setTopP(0.8);
        form.setTopK(25);
        form.setMaxTokens(500);
        form.setSeed(42);
        form.setFrequencyPenalty(0.3);

        var properties = new ChatProperties(Path.of("data/chat.json"), "https://example.test/v1", false);
        var openAiClient = new OpenAiLlmClient(model, properties);
        new ChatService(store, List.of(openAiClient)).send(form);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(promptCaptor.capture());
        Prompt prompt = promptCaptor.getValue();
        assertThat(prompt.getInstructions())
                .extracting(message -> message.getMessageType(), message -> message.getText())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MessageType.SYSTEM, "Current system prompt"),
                        org.assertj.core.groups.Tuple.tuple(MessageType.USER, "Earlier question"),
                        org.assertj.core.groups.Tuple.tuple(MessageType.ASSISTANT, "Earlier answer"),
                        org.assertj.core.groups.Tuple.tuple(MessageType.USER, "New question"));

        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
        assertThat(options.getModel()).isEqualTo("test-model");
        assertThat(options.getTemperature()).isEqualTo(0.4);
        assertThat(options.getTopP()).isEqualTo(0.8);
        assertThat(options.getMaxTokens()).isEqualTo(500);
        assertThat(options.getSeed()).isEqualTo(42);
        assertThat(options.getFrequencyPenalty()).isEqualTo(0.3);
        assertThat(options.getExtraBody()).containsEntry("top_k", 25);

        ArgumentCaptor<ChatConversation> conversationCaptor = ArgumentCaptor.forClass(ChatConversation.class);
        verify(store).save(conversationCaptor.capture());
        assertThat(conversationCaptor.getValue().messages())
                .extracting(ChatMessage::content)
                .containsExactly("Earlier question", "Earlier answer", "New question", "New answer");
    }
}
