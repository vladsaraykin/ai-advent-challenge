package com.github.vladsaraykin.aichat.harness;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.agent.infrastructure.AgentRegistry;
import com.github.vladsaraykin.aichat.harness.application.RagContextService;
import com.github.vladsaraykin.aichat.harness.domain.*;
import com.github.vladsaraykin.aichat.harness.infrastructure.HarnessRequestRepository;
import com.github.vladsaraykin.aichat.rag.application.*;
import com.github.vladsaraykin.aichat.rag.domain.*;
import com.github.vladsaraykin.aichat.rag.infrastructure.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagContextServiceTest {
    @Test void rewriteUsesTaskAndHistoryRetrievesOwnerCorpusAndPreservesUsageOnRerankerFailure() throws Exception {
        var prompt=new AtomicReference<String>();
        var metrics=new ChatMessage.Metrics("fixture",2,0,0,0,10,0,2,12,null,null,null,"stop");
        ConversationModel model=(d,messages)-> { prompt.set(messages.getFirst().content());return new ConversationModel.Reply("Как контролируется PDLC?",metrics); };
        var definition=new AgentRegistry(model,"classpath:agents/*.yaml").get("assistant").definition();
        var embeddings=mock(EmbeddingModel.class);var vector=new float[768];vector[0]=1;
        when(embeddings.embed(anyString())).thenReturn(new EmbeddingModel.Result(vector,7L));
        var repository=mock(RagQuestionRepository.class);
        var source=new RagQuestion.Source(1,new DocumentChunk(UUID.randomUUID(),0,"test.pdf","test","PDLC",1,1,0,22,"Человек контролирует PDLC",4),.9);
        when(repository.searchAll(eq("alice"),eq("embeddinggemma"),any(),eq(20))).thenReturn(List.of(source));
        var selection=new RagSourceSelector(text->text.length()/4,6000);
        var reranker=mock(Reranker.class);
        when(reranker.score(anyString(),anyList())).thenReturn(List.of(.95));
        var usage=mock(HarnessRequestRepository.class);
        var service=new RagContextService(model,embeddings,properties(),provider(repository),provider(selection),provider(reranker),usage);
        var chat=Chat.create("assistant").withWorkingMemory(MemoryService.update(WorkingMemory.empty(),
                new MemoryService.TaskData("Разобраться в PDLC",Map.of(),Map.of("team","2 человека"),Map.of(),List.of()),"",List.of(),null));
        chat=chat.append(new ChatMessage(UUID.randomUUID(),ChatMessage.Role.USER,"Что такое PDLC?",Instant.now(),null),
                new ChatMessage(UUID.randomUUID(),ChatMessage.Role.ASSISTANT,"Жизненный цикл",Instant.now(),null));
        var request=new RequestContext("alice","assistant",chat.id(),UUID.randomUUID(),"А кто это контролирует?",
                new ChatSettings(0,true,List.of(),null),List.of(),null,null);
        var prepared=service.prepare(request,chat,definition);
        assertThat(prepared.sources()).containsExactly(source);
        assertThat(prepared.retrieval().searchQuery()).isEqualTo("Как контролируется PDLC?");
        assertThat(prepared.retrieval().rewriteMetrics().embeddingTokens()).isEqualTo(7L);
        assertThat(prompt.get()).contains("Что такое PDLC?","Разобраться в PDLC","2 человека");
        verify(repository).searchAll(eq("alice"),eq("embeddinggemma"),same(vector),eq(20));
        verify(reranker).score(eq("А кто это контролирует?\nКонтекст вопроса: Как контролируется PDLC?"),anyList());
        verify(usage).usage(request,metrics);
        when(reranker.score(anyString(),anyList())).thenThrow(new Reranker.Failure());
        var contextChat=chat;
        assertThatThrownBy(()->service.prepare(request,contextChat,definition)).isInstanceOf(Reranker.Failure.class);
        verify(usage,times(2)).usage(request,metrics); // Paid rewrite survives downstream failure.
    }
    @Test void ordinaryChatDoesNotRewriteEmbedOrSearch() {
        var model=mock(ConversationModel.class);var embeddings=mock(EmbeddingModel.class);var usage=mock(HarnessRequestRepository.class);
        var service=new RagContextService(model,embeddings,properties(),provider(null),provider(null),provider(null),usage);
        var chat=Chat.create("assistant");
        var request=new RequestContext("alice","assistant",chat.id(),UUID.randomUUID(),"Привет",ChatSettings.defaults(),null,null,null);
        assertThat(service.prepare(request,chat,null)).isSameAs(request);
        verify(model).validateSettings(request.settings());
        verifyNoMoreInteractions(model);
        verifyNoInteractions(embeddings,usage);
    }
    @SuppressWarnings("unchecked") private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(value);when(provider.getObject()).thenReturn(value);return provider;
    }
    private RagProperties properties() {
        return new RagProperties(new RagProperties.Database("jdbc:postgresql://127.0.0.1:5432/test","test","test",1,Duration.ofSeconds(1)),
                new RagProperties.Ollama(java.net.URI.create("http://127.0.0.1:11434"),"embeddinggemma",768,Duration.ofSeconds(1),Duration.ofSeconds(1)));
    }
}
