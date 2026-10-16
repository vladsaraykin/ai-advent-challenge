package com.github.vladsaraykin.aichat.review.application;

import com.github.vladsaraykin.aichat.agent.domain.ChatMessage;
import com.github.vladsaraykin.aichat.rag.domain.RagQuestion;
import com.github.vladsaraykin.aichat.review.domain.*;
import java.util.List;

public final class ReviewRenderer {
    public String render(ReviewRequest request, List<CodeReview.Finding> findings,
                         List<RagQuestion.Source> sources, ChatMessage.Metrics metrics) {
        var out = new StringBuilder("<!-- ai-code-review -->\n## AI-ревью PR #").append(request.pullNumber())
                .append("\n\nКоммит: `").append(request.headSha()).append("`. Проверено файлов: ").append(request.files().size())
                .append(". Контекст RAG: ").append(sources.size()).append(" фрагментов.\n");
        String[][] groups = {{"BUG", "Потенциальные баги"}, {"ARCHITECTURE", "Архитектурные проблемы"},
                {"RECOMMENDATION", "Рекомендации"}};
        for (var group : groups) {
            out.append("\n### ").append(group[1]).append("\n\n");
            var items = findings.stream().filter(f -> f.category().equals(group[0])).toList();
            if (items.isEmpty()) out.append("Подтверждённых замечаний не найдено.\n");
            for (var item : items) {
                String sha = item.side().equals("RIGHT") ? request.headSha() : request.baseSha();
                String path = java.util.Arrays.stream(item.path().split("/")).map(s -> java.net.URLEncoder.encode(s,
                        java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")).collect(java.util.stream.Collectors.joining("/"));
                out.append("- **").append(item.severity()).append("** [").append(escape(item.path())).append(":").append(item.line())
                        .append("](https://github.com/").append(request.repository()).append("/blob/").append(sha).append("/").append(path)
                        .append("#L").append(item.line()).append(") — ").append(escape(item.description()))
                        .append("\n\n  Изменённая строка: ").append(escape(item.quote()))
                        .append("\n\n  Рекомендация: ").append(escape(item.recommendation())).append("\n");
                for (var context : item.context()) out.append("\n  RAG [").append(context.sourceNumber())
                        .append("]: «").append(escape(context.quote())).append("»\n");
            }
        }
        out.append("\n### Использованный контекст\n\n");
        for (var source : sources) out.append("- [").append(source.number()).append("] ").append(escape(source.chunk().source()))
                .append("; chunk `").append(source.chunk().chunkId()).append("`\n");
        var noPatch = request.files().stream().filter(f -> f.patch().isBlank()).map(ReviewRequest.ChangedFile::path).toList();
        if (!noPatch.isEmpty()) out.append("\nБез текстового diff (не проверены): ").append(escape(String.join(", ", noPatch))).append(".\n");
        if (metrics != null) out.append("\nМодель: ").append(escape(metrics.model())).append("; время: ").append(metrics.durationMs())
                .append(" мс; токены: ").append(metrics.totalTokens()).append("; USD: ").append(metrics.totalCostUsd()).append(".\n");
        out.append("\nРевью не запускает тесты и не гарантирует отсутствие ошибок. Цитаты и координаты проверены сервером; выводы оценивает человек.")
                .append(" RAG содержит текущий индекс проекта, а не гарантированный снимок base SHA PR.\n");
        return out.toString();
    }
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\\", "\\\\").replace("`", "\\`").replace("*", "\\*").replace("_", "\\_")
                .replace("[", "\\[").replace("]", "\\]").replace("!", "\\!").replace("#", "\\#")
                .replace("\r", " ").replace("\n", " ").replace("@", "&#64;");
    }
}
