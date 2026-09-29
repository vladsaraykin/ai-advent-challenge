package com.github.vladsaraykin.aichat.rag.application;

import com.github.vladsaraykin.aichat.rag.domain.*;
import com.github.vladsaraykin.aichat.user.application.UserRepository;
import com.github.vladsaraykin.aichat.user.domain.UserProfile;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagQuestionServiceTest {
    final IndexRepository indexes=mock(IndexRepository.class);
    final RagQuestionRepository repository=mock(RagQuestionRepository.class);
    final EmbeddingModel embeddings=mock(EmbeddingModel.class);
    final UserRepository users=mock(UserRepository.class);
    final UUID index=UUID.randomUUID();
    final RagAnswerSettings settings=new RagAnswerSettings("gpt-6.1-sol",4096,30,5,6000,null,null,null);
    final RagQuestion.Metrics metrics=new RagQuestion.Metrics("gpt-6.1-sol",10,0,0,null,20,10,30,0,null,"stop");
    RagQuestionService service(RagAnswerModel model) {
        when(indexes.get("alice",index)).thenReturn(new IndexRun(index,UUID.randomUUID(),DocumentChunk.Strategy.FIXED_SIZE,
                "COMPLETED","embeddinggemma:latest",768,1,1,5L,1L,null,Instant.now(),Instant.now()));
        when(users.profile("alice")).thenReturn(UserProfile.initial("alice"));
        when(repository.create(eq("alice"),any())).thenReturn(true);
        return new RagQuestionService(indexes,repository,embeddings,"embeddinggemma",model,settings,String::length,users);
    }
    RagQuestionService.Request request(RagQuestion.Mode mode) { return new RagQuestionService.Request(UUID.randomUUID(),index,"Вопрос",mode); }
    @Test void baselineNeverRetrievesAndBothModesUseIndependentContexts() throws Exception {
        var prompts=new CopyOnWriteArrayList<String>();
        try(var service=service((system,user,delta) -> { prompts.add(user); assertThat(system).contains("Кратко"); delta.accept("Ответ"); return new RagAnswerModel.Result("Ответ",metrics); })) {
            run(service,request(RagQuestion.Mode.WITHOUT_RAG));
            verifyNoInteractions(embeddings);
            verify(repository,never()).search(any(),any(),any(),anyInt());
            var chunk=new DocumentChunk(UUID.randomUUID(),0,"test.pdf","test.pdf","Section",1,1,0,8,"Документ",4);
            when(embeddings.embed("Вопрос")).thenReturn(new EmbeddingModel.Result(new float[768],3L));
            when(repository.search(eq("alice"),eq(index),any(),eq(5))).thenReturn(List.of(new RagQuestion.Source(1,chunk,.85)));
            var result=run(service,request(RagQuestion.Mode.BOTH));
            assertThat(result.answers()).hasSize(2);
            assertThat(prompts.get(1)).isEqualTo("Вопрос").doesNotContain("Ответ","Документ");
            assertThat(prompts.get(2)).contains("Документ","question").doesNotContain("Ответ");
            assertThat(result.answers().getLast().metrics().embeddingTokens()).isEqualTo(3);
            assertThat(result.answers().getFirst().sources()).isEmpty();
        }
    }
    @Test void failedRagKeepsBaselineAndDoesNotPersistPartialAnswer() throws Exception {
        try(var service=service((system,user,delta) -> new RagAnswerModel.Result("Ответ",metrics))) {
            when(embeddings.embed(any())).thenThrow(new EmbeddingModel.Failure("secret"));
            var result=run(service,request(RagQuestion.Mode.BOTH));
            assertThat(result.status()).isEqualTo("PARTIAL");
            assertThat(result.answers().getFirst().text()).isEqualTo("Ответ");
            assertThat(result.answers().getLast().text()).isEmpty();
            assertThat(result.answers().getLast().error()).doesNotContain("secret");
        }
    }
    @Test void retryReplaysCompletedRequestWithoutProviderAndRejectsForeignIndex() throws Exception {
        var model=mock(RagAnswerModel.class);
        try(var service=service(model)) {
            var request=request(RagQuestion.Mode.WITHOUT_RAG);
            var prior=new RagQuestion(request.id(),index,"Вопрос",request.mode(),"COMPLETED",List.of(),Instant.now());
            when(repository.create(eq("alice"),any())).thenReturn(false);
            when(repository.find("alice",request.id())).thenReturn(Optional.of(prior));
            assertThat(run(service,request)).isEqualTo(prior); verifyNoInteractions(model,embeddings);
            when(indexes.get("bob",index)).thenThrow(new RagFailure(RagFailure.Kind.NOT_FOUND,"Missing"));
            assertThatThrownBy(() -> service.start("bob",request,(e,d)->{},()->false)).isInstanceOf(RagFailure.class);
        }
    }
    @Test void rejectsModelMismatchAndIncompleteIndexesBeforeEmbedding() {
        try(var service=service(mock(RagAnswerModel.class))) {
            when(indexes.get("alice",index)).thenReturn(new IndexRun(index,UUID.randomUUID(),DocumentChunk.Strategy.FIXED_SIZE,
                    "COMPLETED","other",768,1,1,null,null,null,Instant.now(),Instant.now()));
            assertThatThrownBy(() -> service.start("alice",request(RagQuestion.Mode.WITH_RAG),(e,d)->{},()->false)).hasMessageContaining("отличается");
            verifyNoInteractions(embeddings);
        }
    }
    @Test void contextBudgetRetainsWholeChunksAndRenumbers() {
        try(var service=service(mock(RagAnswerModel.class))) {
            var big=new DocumentChunk(UUID.randomUUID(),0,"s","t","",null,null,0,7000,"x".repeat(7000),7000);
            var small=new DocumentChunk(UUID.randomUUID(),1,"s","t","",null,null,0,5,"small",5);
            var result=service.select(List.of(new RagQuestion.Source(1,big,.9),new RagQuestion.Source(2,small,.8)));
            assertThat(result).hasSize(1); assertThat(result.getFirst().number()).isEqualTo(1);
            assertThat(result.getFirst().chunk()).isEqualTo(small);
        }
    }
    @Test void disconnectAfterFinalSaveDoesNotOverwriteCompletedResult() throws Exception {
        var done=new CountDownLatch(1);
        try(var service=service((system,user,delta) -> new RagAnswerModel.Result("Ответ",metrics))) {
            service.start("alice",request(RagQuestion.Mode.WITHOUT_RAG),(event,data) -> {
                if(event.equals("completed")) throw new IllegalStateException("Disconnected");
                if(event.equals("error")) done.countDown();
            },()->false);
            assertThat(done.await(5,TimeUnit.SECONDS)).isTrue();
            verify(repository).save(eq("alice"),argThat(value -> value.status().equals("COMPLETED")));
            verify(repository,never()).save(any(),argThat(value -> value.status().equals("INTERRUPTED")));
        }
    }
    @Test void boundsConcurrentRequestsAndAllowsEmptyRetrieval() throws Exception {
        var entered=new CountDownLatch(2); var release=new CountDownLatch(1);
        try(var service=service((system,user,delta) -> {
            entered.countDown();
            try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            return new RagAnswerModel.Result("Ответ",metrics);
        })) {
            service.start("alice",request(RagQuestion.Mode.WITHOUT_RAG),(e,d)->{},()->false);
            service.start("alice",request(RagQuestion.Mode.WITHOUT_RAG),(e,d)->{},()->false);
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.start("alice",request(RagQuestion.Mode.WITHOUT_RAG),(e,d)->{},()->false))
                    .isInstanceOf(RagFailure.class).hasMessageContaining("два");
            release.countDown();
            assertThat(service.select(List.of())).isEmpty();
        } finally { release.countDown(); }
    }
    private RagQuestion run(RagQuestionService service,RagQuestionService.Request request) throws Exception {
        var done=new CountDownLatch(1); var result=new AtomicReference<RagQuestion>();
        service.start("alice",request,(event,data) -> { if(event.equals("completed")){ result.set((RagQuestion)data); done.countDown(); } },()->false);
        assertThat(done.await(5,TimeUnit.SECONDS)).isTrue(); return result.get();
    }
}
