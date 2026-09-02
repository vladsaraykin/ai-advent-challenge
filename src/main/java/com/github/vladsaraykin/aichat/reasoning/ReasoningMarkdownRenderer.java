package com.github.vladsaraykin.aichat.reasoning;

import java.util.List;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

@Component
public class ReasoningMarkdownRenderer {

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
    private final Parser parser = Parser.builder().extensions(EXTENSIONS).build();
    private final HtmlRenderer renderer = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();

    public List<RenderedReasoningTurn> render(List<ReasoningTurn> turns) {
        return turns.stream().map(this::render).toList();
    }

    private RenderedReasoningTurn render(ReasoningTurn turn) {
        boolean user = turn.role().startsWith("Пользователь");
        String html = user ? null : renderer.render(parser.parse(turn.content()));
        return new RenderedReasoningTurn(turn.role(), turn.content(), html, user);
    }
}
