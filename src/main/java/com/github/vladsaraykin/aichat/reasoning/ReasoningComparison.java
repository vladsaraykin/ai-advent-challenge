package com.github.vladsaraykin.aichat.reasoning;

import java.util.List;

public record ReasoningComparison(String task, String model, List<ApproachResult> results,
                                  String summary, TokenUsage usage, long durationMillis) {
    public ReasoningComparison {
        results = List.copyOf(results);
    }

    public String durationLabel() {
        return DurationFormatter.format(durationMillis);
    }

    public String markdown() {
        StringBuilder markdown = new StringBuilder("# Сравнение способов рассуждения\n\n")
                .append("## Задача\n\n").append(task).append("\n\n")
                .append("Модель: `").append(model).append("`\n\n");
        for (ApproachResult result : results) {
            markdown.append("## ").append(result.approach().getDisplayName()).append("\n\n");
            for (ReasoningTurn turn : result.turns()) {
                markdown.append("### ").append(turn.role()).append("\n\n")
                        .append(turn.content()).append("\n\n");
            }
            markdown.append("**Токены:** prompt ").append(result.usage().promptTokens())
                    .append(", completion ").append(result.usage().completionTokens())
                    .append(", всего ").append(result.usage().totalTokens()).append("  \n")
                    .append("**Время:** ").append(result.durationLabel()).append("\n\n");
        }
        return markdown.append("## Сравнение\n\n").append(summary).append("\n\n")
                .append("**Всего токенов (включая сравнительный анализ):** ")
                .append(usage.totalTokens()).append("  \n")
                .append("**Общее время:** ").append(durationLabel()).append("\n").toString();
    }
}
