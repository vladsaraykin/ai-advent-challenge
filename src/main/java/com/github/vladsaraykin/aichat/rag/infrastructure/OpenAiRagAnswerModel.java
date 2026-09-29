package com.github.vladsaraykin.aichat.rag.infrastructure;

import com.github.vladsaraykin.aichat.rag.application.RagAnswerModel;
import com.github.vladsaraykin.aichat.rag.domain.*;
import com.github.vladsaraykin.aichat.agent.domain.TokenCostCalculator;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/** No ChatClient, memory, tools or callbacks: same options in both experimental modes. */
public final class OpenAiRagAnswerModel implements RagAnswerModel {
    private final ChatModel model;
    private final RagAnswerSettings settings;
    public OpenAiRagAnswerModel(ChatModel model, RagAnswerSettings settings) { this.model = model; this.settings = settings; }
    @Override public Result answer(String system, String user, Consumer<String> delta) {
        var text = new StringBuilder();
        var tokens = new Integer[4];
        var finish = new String[1];
        long started = System.nanoTime();
        // No tools are attached: omit tool_choice entirely (some providers reject even "none").
        var options = OpenAiChatOptions.builder().model(settings.model()).maxCompletionTokens(settings.maxCompletionTokens())
                .streamUsage(true).maxRetries(0).serviceTier("default")
                .timeout(Duration.ofSeconds(settings.timeoutSeconds())).build();
        model.stream(new Prompt(List.of(new SystemMessage(system), new UserMessage(user)), options))
                .doOnNext(response -> {
                    var usage = response.getMetadata().getUsage();
                    if (usage != null && ((usage.getPromptTokens() != null && usage.getPromptTokens() > 0)
                            || (usage.getCompletionTokens() != null && usage.getCompletionTokens() > 0))) {
                        tokens[0] = usage.getPromptTokens(); tokens[1] = usage.getCompletionTokens(); tokens[2] = usage.getTotalTokens();
                        tokens[3] = usage.getCacheReadInputTokens() == null ? 0 : Math.toIntExact(usage.getCacheReadInputTokens());
                    }
                    if (response.getResult() != null) {
                        var reason = response.getResult().getMetadata().getFinishReason();
                        if (reason != null && !reason.isBlank()) finish[0] = reason.toLowerCase(java.util.Locale.ROOT);
                        var value = response.getResult().getOutput().getText();
                        if (value != null && !value.isEmpty()) {
                            if (text.length() + value.length() > 120000) throw new IllegalStateException("Answer too large");
                            text.append(value); delta.accept(value);
                        }
                    }
                }).blockLast(Duration.ofSeconds(settings.timeoutSeconds()));
        if (text.isEmpty() || finish[0] == null) throw new IllegalStateException("Incomplete answer");
        var cost = settings.pricing() == null || tokens[0] == null || tokens[1] == null ? null
                : TokenCostCalculator.calculate(settings.pricing(), tokens[0], tokens[3], tokens[1]).totalUsd();
        return new Result(text.toString(), new RagQuestion.Metrics(settings.model(), (System.nanoTime()-started)/1_000_000,
                0, 0, null, tokens[0], tokens[1], tokens[2], tokens[3], cost, finish[0]));
    }
}
