package com.github.vladsaraykin.aichat.rag.infrastructure;

import com.github.vladsaraykin.aichat.rag.application.Reranker;
import com.github.vladsaraykin.aichat.rag.domain.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class HttpRerankerTest {
    @Test void sendsOrderedDocumentsAndRejectsInvalidUpstreamWithoutExposingBodies() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var reply=new java.util.concurrent.atomic.AtomicReference<>("{\"scores\":[0.1,0.9]}");
        server.createContext("/rerank",exchange -> {
            var body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            assertThat(body).contains("query","Вопрос","first","second");
            var bytes=reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);
            try(var output=exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try(var client=new HttpReranker(new RerankerSettings(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),5,20,5,.2))) {
            assertThat(client.score("Вопрос",List.of("first","second"))).containsExactly(.1,.9);
            for(String invalid:List.of("{\"scores\":[0.5]}","{\"scores\":[-1,2]}","{\"scores\":[\"secret\",0.5]}","secret")) {
                reply.set(invalid);
                assertThatThrownBy(() -> client.score("Вопрос",List.of("first","second"))).isInstanceOf(Reranker.Failure.class).hasMessageNotContaining("secret");
            }
            assertThat(client.score("q",List.of())).isEmpty();
        } finally { server.stop(0); }
    }
    @Test void newAndOldHistoryRoundTripWithoutMigration() {
        var json=JsonMapper.builder().build();
        var q=new RagQuestion(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),"q",RagQuestion.Mode.COMPARE,
                "COMPLETED",List.of(new RagQuestion.Answer(RagQuestion.Mode.REWRITTEN,"answer",List.of(),null,null,
                new RagQuestion.Retrieval("rewritten",4,null,new RetrievalOptions(20,5,.2),List.of()))),java.time.Instant.now(),new RetrievalOptions(20,5,.2));
        assertThat(json.readValue(json.writeValueAsString(q),RagQuestion.class)).isEqualTo(q);
        var legacy=json.writeValueAsString(q).replace(",\"retrievalOptions\":{\"candidateK\":20,\"finalK\":5,\"threshold\":0.2}","");
        assertThat(json.readValue(legacy,RagQuestion.class).retrievalOptions()).isNull();
        var old=new RagQuestion(q.id(),q.indexId(),"q",RagQuestion.Mode.WITH_RAG,"COMPLETED",
                List.of(new RagQuestion.Answer(RagQuestion.Mode.WITH_RAG,"answer",List.of(),null,null)),q.createdAt());
        var oldJson=json.writeValueAsString(old).replace(",\"retrievalOptions\":null","").replace(",\"retrieval\":null","");
        assertThat(json.readValue(oldJson,RagQuestion.class)).isEqualTo(old);
    }
}
