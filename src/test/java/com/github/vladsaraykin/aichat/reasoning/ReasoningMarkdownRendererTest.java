package com.github.vladsaraykin.aichat.reasoning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReasoningMarkdownRendererTest {

    private final ReasoningMarkdownRenderer renderer = new ReasoningMarkdownRenderer();

    @Test
    void rendersAssistantMarkdownIncludingTablesAndEscapesRawHtml() {
        String markdown = """
                ## Итог

                | Подход | Балл |
                |---|---:|
                | Прямой | 8 |

                <script>alert('xss')</script>
                """;

        RenderedReasoningTurn turn = renderer.render(
                List.of(new ReasoningTurn("Ассистент", markdown))).getFirst();

        assertThat(turn.html()).contains("<h2>Итог</h2>", "<table>", "<td>Прямой</td>")
                .contains("&lt;script&gt;")
                .doesNotContain("<script>");
    }

    @Test
    void keepsUserPromptAsPlainText() {
        RenderedReasoningTurn turn = renderer.render(
                List.of(new ReasoningTurn("Пользователь", "**не форматировать**"))).getFirst();

        assertThat(turn.user()).isTrue();
        assertThat(turn.html()).isNull();
        assertThat(turn.content()).isEqualTo("**не форматировать**");
    }
}
