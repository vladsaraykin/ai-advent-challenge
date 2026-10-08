package com.github.vladsaraykin.aichat.agent.infrastructure;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.harness.domain.RequestContext;
import com.github.vladsaraykin.aichat.mcp.application.McpToolService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Reuses the protocol/accounting adapter with a separate client and MLX-specific options. */
@Component
public class LocalMlxConversationModel extends OpenAiConversationModel {
    private final LocalLlmProperties settings;
    private final Semaphore permits;

    @org.springframework.beans.factory.annotation.Autowired
    public LocalMlxConversationModel(LocalLlmProperties settings, TokenCounter tokens, McpToolService tools) {
        this(settings,tokens,tools,OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                .baseUrl(settings.baseUrl()).apiKey("local-no-api-key")
                .model(settings.model()).maxRetries(0)
                .timeout(Duration.ofSeconds(settings.timeoutSeconds())).build()).build());
    }
    LocalMlxConversationModel(LocalLlmProperties settings,TokenCounter tokens,McpToolService tools,
                              org.springframework.ai.chat.model.ChatModel model) {
        super(model,tokens,tools);
        this.settings = settings;
        this.permits = new Semaphore(settings.concurrency());
    }

    @Override protected boolean localProvider() { return true; }

    @Override protected Prompt providerPrompt(Prompt request) {
        var original = (OpenAiChatOptions) request.getOptions();
        var options = OpenAiChatOptions.builder().model(settings.model())
                .maxTokens(Math.min(original.getMaxCompletionTokens(), settings.maxCompletionTokens()))
                .temperature(original.getResponseFormat()==null ? settings.temperature() : 0.0)
                .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .maxRetries(0).streamUsage(true)
                .extraBody(Map.of("chat_template_kwargs", Map.of("enable_thinking", settings.thinking())))
                .build();
        // MLX does not enforce response_format. Domain validators still validate the generated JSON.
        // The Qwen chat template accepts one system message at the beginning.
        var system=new StringBuilder();
        var instructions=new java.util.ArrayList<org.springframework.ai.chat.messages.Message>();
        for(var message:request.getInstructions()) {
            if(message.getMessageType()==org.springframework.ai.chat.messages.MessageType.SYSTEM) {
                if(!system.isEmpty()) system.append("\n\n");
                system.append(message.getText());
            } else instructions.add(message);
        }
        if(original.getResponseFormat()!=null) {
            boolean questions=request.getInstructions().getFirst().getText().contains("\"questions\"");
            system.append("\n\n").append(questions ? settings.questionsInstruction() : settings.structuredOutputInstruction());
        }
        if(!system.isEmpty()) instructions.addFirst(new org.springframework.ai.chat.messages.SystemMessage(system.toString()));
        return new Prompt(instructions, options);
    }

    @Override public Reply reply(AgentDefinition definition, ContextSummary summary, List<ChatMessage> messages) {
        acquire();
        try {
            var reply=super.reply(definition, summary, messages);
            return new Reply(reply.text(),reply.metrics().asLocal());
        }
        finally { permits.release(); }
    }

    @Override public Flux<StreamPart> extractQuestions(AgentDefinition definition,List<ChatMessage> messages) {
        // This operation extracts quotes solely from the assistant reply, not from task/user text.
        // Removing unrelated input prevents small models from copying old task questions as new quotes.
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var focused=messages.stream().map(message->{
            try {
                var input=json.readTree(message.content());
                if(input.isObject() && input.path("assistantAnswer").isString())
                    return new ChatMessage(message.id(),message.role(),
                            json.writeValueAsString(Map.of("assistantAnswer",input.path("assistantAnswer").asString())),
                            message.createdAt(),message.metrics());
            } catch(RuntimeException ignored) { /* Direct protocol tests may pass a plain-text input. */ }
            return message;
        }).toList();
        return super.extractQuestions(definition,focused);
    }

    @Override public Flux<StreamPart> extractFacts(AgentDefinition definition,List<ChatMessage> messages) {
        return streamCall(definition,null,messages,memoryPrompt(definition,messages),
                definition.systemPrompt(),"llm_facts",null,null);
    }

    @Override protected Flux<StreamPart> streamCall(AgentDefinition definition, ContextSummary summary,
            List<ChatMessage> messages, Prompt request, String systemPrompt, String operation,
            List<String> servers, RequestContext context) {
        return Flux.defer(() -> {
            if (servers != null && !servers.isEmpty() && !settings.toolsEnabled()) {
                return Flux.error(new ChatFailure(ChatFailure.Kind.INVALID,
                        "MCP для локальной модели отключён. Уберите MCP-серверы или включите LOCAL_LLM_TOOLS_ENABLED после проверки tool calls."));
            }
            acquire();
            var released=new java.util.concurrent.atomic.AtomicBoolean();
            Runnable release=()->{ if(released.compareAndSet(false,true)) permits.release(); };
            return super.streamCall(definition, summary, messages, request, systemPrompt, operation, servers, context)
                    .map(part -> {
                        if(part.completed()==null) return part;
                        release.run();
                        var reply=part.completed();
                        return StreamPart.completed(new Reply(reply.text(),reply.metrics().asLocal()));
                    }).doFinally(signal -> release.run());
        });
    }

    private void acquire() {
        if (!settings.enabled()) throw new ChatFailure(ChatFailure.Kind.INVALID,"Локальная модель отключена на сервере.");
        if (!permits.tryAcquire()) throw new ChatFailure(ChatFailure.Kind.BUSY,
                "Локальная модель занята другим запросом. Повторите отправку после его завершения.");
    }
}
