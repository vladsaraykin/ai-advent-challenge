package com.github.vladsaraykin.aichat.review;

import com.github.vladsaraykin.aichat.review.application.*;
import com.github.vladsaraykin.aichat.review.domain.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReviewValidatorTest {
    @Test void acceptsLabelledPartialContextAndReportsItsCoverageWithoutRequiringWholeFiles() {
        var original = CodeReviewServiceTest.request();
        var file = original.files().getFirst();
        var request = new ReviewRequest(original.requestId(), original.repository(), original.pullNumber(), original.baseSha(),
                original.headSha(), original.title(), List.of(new ReviewRequest.ChangedFile(file.path(), file.status(), file.patch(),
                "PARTIAL FILE CONTEXT; RIGHT; 10000 total lines. Unlisted lines are omitted.\nL2:   savePartial();")));
        assertThat(validator.validate(CodeReviewServiceTest.FINDING, request, List.of(CodeReviewServiceTest.SOURCE))).hasSize(1);
        assertThat(new ReviewRenderer().render(request, List.of(), List.of(CodeReviewServiceTest.SOURCE), null))
                .contains("Diff передан полностью", "фрагменты у 1", "отсутствует у 0");
    }
    private final ReviewValidator validator = new ReviewValidator();
    @Test void acceptsOnlyExactChangedLinesAndSourceQuotes() {
        assertThat(validator.validate(CodeReviewServiceTest.FINDING, CodeReviewServiceTest.request(), List.of(CodeReviewServiceTest.SOURCE))).hasSize(1);
        for (String bad : List.of(CodeReviewServiceTest.FINDING.replace("\"line\":2", "\"line\":1"),
                CodeReviewServiceTest.FINDING.replace("src/Example.java", "src/Unknown.java"),
                CodeReviewServiceTest.FINDING.replace("savePartial();", "invented();"),
                CodeReviewServiceTest.FINDING.replace("\"sourceNumber\":1", "\"sourceNumber\":99"),
                CodeReviewServiceTest.FINDING.replace("\"RIGHT\"", "\"LEFT\""),
                CodeReviewServiceTest.FINDING + "{}", "{\"findings\":[],\"findings\":[]}")) {
            assertThatThrownBy(() -> validator.validate(bad, CodeReviewServiceTest.request(), List.of(CodeReviewServiceTest.SOURCE)))
                    .hasMessage("review_validation_failed");
        }
    }
    @Test void acceptsDeletedLinesOnLeftAndEmptyFindingsWithoutInventingIssues() {
        String left = CodeReviewServiceTest.FINDING.replace("\"RIGHT\"", "\"LEFT\"").replace("savePartial();", "saveComplete();");
        assertThat(validator.validate(left, CodeReviewServiceTest.request(), List.of(CodeReviewServiceTest.SOURCE))).hasSize(1);
        assertThat(validator.validate("{\"findings\":[]}", CodeReviewServiceTest.request(), List.of(CodeReviewServiceTest.SOURCE))).isEmpty();
    }
    @Test void rejectsOversizedRequestsAndEscapingPaths() {
        var r = CodeReviewServiceTest.request();
        for (String path : List.of("../secret", "/etc/passwd", "src\nfile"))
            assertThatThrownBy(() -> new ReviewRequest(r.requestId(), r.repository(), 1, r.baseSha(), r.headSha(), r.title(),
                    List.of(new ReviewRequest.ChangedFile(path, "added", "@@ -0,0 +1 @@\n+x", "x"))))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewRequest(r.requestId(), r.repository(), 1, r.baseSha(), r.headSha(), r.title(),
                List.of(new ReviewRequest.ChangedFile("big.java", "modified", "x", "x".repeat(120001)))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rendererEscapesUntrustedHtmlAndMentions() {
        var finding = new CodeReview.Finding("BUG", "HIGH", "src/Example.java", "RIGHT", 2, "savePartial();",
                "<script>@everyone</script>", "[click](javascript:alert(1))", List.of());
        assertThat(new ReviewRenderer().render(CodeReviewServiceTest.request(), List.of(finding), List.of(CodeReviewServiceTest.SOURCE), null))
                .doesNotContain("<script>", "@everyone", "[click](javascript:").contains("&lt;script&gt;", "&#64;everyone");
    }
}
