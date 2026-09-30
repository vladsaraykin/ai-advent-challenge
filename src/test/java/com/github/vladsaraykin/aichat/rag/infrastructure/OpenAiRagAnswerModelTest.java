package com.github.vladsaraykin.aichat.rag.infrastructure;

import com.github.vladsaraykin.aichat.rag.domain.RagAnswerSettings;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenAiRagAnswerModelTest {
    RagAnswerSettings settings=new RagAnswerSettings("gpt-6.1-sol",4096,5,5,6000,new BigDecimal("2"),new BigDecimal("0.1"),new BigDecimal("10"));
    @Test void streamsExactModelCountsTokensAndUsesIdenticalNoToolOptions() {
        var model=mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt=call.getArgument(0); var options=(OpenAiChatOptions)prompt.getOptions();
            assertThat(options.getModel()).isEqualTo("gpt-6.1-sol");
            assertThat(options.getMaxCompletionTokens()).isEqualTo(4096); assertThat(options.getMaxTokens()).isNull();
            assertThat(options.getServiceTier()).isEqualTo("default");
            assertThat(options.getToolChoice()).as("tool_choice must be omitted without tools").isNull();
            assertThat(options.getToolCallbacks()).as("RAG must not attach MCP tools").isNullOrEmpty();
            assertThat(options.getTemperature()).isNull(); assertThat(options.getMaxRetries()).isZero();
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("Ответ"),
                    ChatGenerationMetadata.builder().finishReason("STOP").build())),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(100,20,120)).build()));
        });
        var text=new StringBuilder(); var answer=new OpenAiRagAnswerModel(model,settings).answer("system","question",text::append);
        assertThat(text.toString()).isEqualTo("Ответ"); assertThat(answer.metrics().costUsd()).isEqualByComparingTo("0.0004");
        assertThat(answer.metrics().finishReason()).isEqualTo("stop");
        new OpenAiRagAnswerModel(model,settings).answer("system with sources", "{\"sources\":[\"document\"],\"question\":\"question\"}", part -> {});
        verify(model,times(2)).stream(any(Prompt.class));
    }
    @Test void interruptedProviderStreamCannotBecomeCompletedResult() {
        var model=mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("partial"))))));
        assertThatThrownBy(() -> new OpenAiRagAnswerModel(model,settings).answer("system","q",part -> {})).hasMessageContaining("Incomplete");
    }
    @Test void rewriteHasOwnLimitAndUsageWithoutToolsOrAnswerStreaming() {
        var model=mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt=call.getArgument(0);
            var options=(OpenAiChatOptions)prompt.getOptions();
            assertThat(options.getMaxCompletionTokens()).isEqualTo(1024);
            assertThat(options.getToolCallbacks()).isNullOrEmpty();
            assertThat(options.getToolChoice()).isNull();
            assertThat(prompt.getContents()).contains("Сохрани смысл","Не отвечай");
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("Поисковая формулировка"),
                    ChatGenerationMetadata.builder().finishReason("STOP").build())),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(100,20,120)).build()));
        });
        var result=new OpenAiRagAnswerModel(model,settings).rewrite("Вопрос");
        assertThat(result.text()).isEqualTo("Поисковая формулировка");
        assertThat(result.metrics().totalTokens()).isEqualTo(120);
        assertThat(result.metrics().costUsd()).isEqualByComparingTo("0.0004");
    }
}
