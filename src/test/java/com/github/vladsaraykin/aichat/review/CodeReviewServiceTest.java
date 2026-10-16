package com.github.vladsaraykin.aichat.review;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.agent.infrastructure.AgentRegistry;
import com.github.vladsaraykin.aichat.rag.domain.*;
import com.github.vladsaraykin.aichat.review.application.*;
import com.github.vladsaraykin.aichat.review.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodeReviewServiceTest {
    static final String REPO = "vladsaraykin/ai-advent-challenge";
    static final RagQuestion.Source SOURCE = new RagQuestion.Source(1, new DocumentChunk(UUID.randomUUID(), 0,
            "project__architecture.md", "Architecture", "Transactions", null, null, 0, 100,
            "Persist completed turns atomically in PostgreSQL.", 10), .9);
    static final String FINDING = """
            {"findings":[{"category":"ARCHITECTURE","severity":"HIGH","path":"src/Example.java","side":"RIGHT",
              "line":2,"quote":"savePartial();","description":"При сбое останется частичный ответ.",
              "recommendation":"Сохранять только завершённый ответ атомарно.",
              "context":[{"sourceNumber":1,"quote":"Persist completed turns atomically"}]}]}
            """;
    static ReviewRequest request() {
        return new ReviewRequest(UUID.randomUUID(), REPO, 1, "a".repeat(40), "b".repeat(40), "Change persistence",
                List.of(new ReviewRequest.ChangedFile("src/Example.java", "modified",
                        "@@ -1,2 +1,2 @@\n class Example {\n-  saveComplete();\n+  savePartial();", "class Example {\n  savePartial();")));
    }
    private final ConversationModel model = mock(ConversationModel.class);
    private final ReviewContextService context = mock(ReviewContextService.class);
    private final ReviewRepository repository = mock(ReviewRepository.class);
    private final ChatMessage.Metrics metrics = new ChatMessage.Metrics("fixture", 3, 0, 0, 0,
            100, 0, 20, 120, null, null, null, "stop");
    private CodeReviewService service() throws Exception {
        when(repository.begin(anyString(), any(), anyString())).thenReturn(true);
        when(context.retrieve(anyString(), any())).thenReturn(List.of(SOURCE));
        when(model.reviewCode(any(), anyList())).thenReturn(Flux.just(ConversationModel.StreamPart.completed(new ConversationModel.Reply(FINDING, metrics))));
        return new CodeReviewService(new AgentRegistry(model, "classpath:review-agents/*.yaml").get("code-reviewer").definition(),
                model, context, repository, REPO);
    }
    @Test void sendsDiffFilesAndOwnerScopedRagAndPersistsUsageBeforeValidation() throws Exception {
        var service = service();
        var request = request();
        var result = service.review("vlad", request);
        assertThat(result.findings()).hasSize(1);
        assertThat(result.markdown()).contains("Потенциальные баги", "Архитектурные проблемы", "Рекомендации", "#L2", "RAG [1]");
        var input = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(model).reviewCode(argThat(d -> d.id().equals("code-reviewer")), input.capture());
        assertThat(((ChatMessage) input.getValue().getFirst()).content()).contains("savePartial", "Change persistence", SOURCE.chunk().content());
        var order = inOrder(repository);
        order.verify(repository).usage("vlad", request.requestId(), metrics);
        order.verify(repository).complete(eq("vlad"), any());
        verify(context).retrieve("vlad", request);
    }
    @Test void completedRetryDoesNotCallModelAndDifferentPayloadCannotReuseId() throws Exception {
        var service = service(); var request = request();
        service.review("vlad", request);
        var sha = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository).begin(eq("vlad"), eq(request), sha.capture());
        var completed = org.mockito.ArgumentCaptor.forClass(CodeReview.class);
        verify(repository).complete(eq("vlad"), completed.capture());
        when(repository.find("vlad", request.requestId())).thenReturn(Optional.of(new ReviewRepository.Entry(sha.getValue(), "COMPLETED", completed.getValue())));
        assertThat(service.review("vlad", request)).isEqualTo(completed.getValue());
        verify(model, times(1)).reviewCode(any(), anyList());
        var altered = new ReviewRequest(request.requestId(), REPO, 2, request.baseSha(), request.headSha(), request.title(), request.files());
        assertThatThrownBy(() -> service.review("vlad", altered)).isInstanceOf(ChatFailure.class).hasMessageContaining("других данных");
    }
    @Test void invalidQuoteWithholdsReportButRetainsPaidUsage() throws Exception {
        var service = service(); var request = request();
        when(model.reviewCode(any(), anyList())).thenReturn(Flux.just(ConversationModel.StreamPart.completed(
                new ConversationModel.Reply(FINDING.replace("Persist completed turns atomically", "Invented unsupported architecture"), metrics))));
        assertThatThrownBy(() -> service.review("vlad", request)).hasMessageContaining("review_validation_failed");
        verify(repository).usage("vlad", request.requestId(), metrics);
        verify(repository).fail("vlad", request.requestId(), "review_validation_failed");
        verify(repository, never()).complete(anyString(), any());
    }
    @Test void retrievalFailureDoesNotGenerateAnUngroundedReviewAndErrorsAreSanitized() throws Exception {
        var service = service(); var request = request();
        when(context.retrieve("vlad", request)).thenThrow(new IllegalStateException("secret api-key raw response"));
        assertThatThrownBy(() -> service.review("vlad", request)).hasMessageNotContaining("secret").hasMessageContaining("review_dependency_failed");
        verifyNoInteractions(model);
        verify(repository).fail("vlad", request.requestId(), "review_dependency_failed");
    }
    @Test void ownerIsolationAndRepositoryAllowlistAreEnforced() throws Exception {
        var service = service(); var request = request();
        assertThatThrownBy(() -> service.get("other", request.requestId())).isInstanceOf(ChatFailure.class);
        verify(repository).find("other", request.requestId());
        var foreign = new ReviewRequest(request.requestId(), "someone/other", 1, request.baseSha(), request.headSha(), request.title(), request.files());
        assertThatThrownBy(() -> service.review("vlad", foreign)).hasMessageContaining("не разрешён");
        verifyNoInteractions(context, model);
    }
    @Test void anActiveReviewRejectsConcurrentRequestsAndReleasesItsSlotAfterFailure() throws Exception {
        var service = service(); var first = request();
        when(context.retrieve("vlad", first)).thenAnswer(call -> {
            assertThatThrownBy(() -> service.review("vlad", request())).hasMessageContaining("ещё выполняется");
            throw new IllegalStateException("project_index_empty");
        });
        assertThatThrownBy(() -> service.review("vlad", first)).hasMessageContaining("project_index_empty");
        assertThat(service.review("vlad", request()).findings()).hasSize(1);
    }
}
