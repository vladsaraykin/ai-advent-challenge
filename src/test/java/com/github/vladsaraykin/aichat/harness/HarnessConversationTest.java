package com.github.vladsaraykin.aichat.harness;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.agent.infrastructure.*;
import com.github.vladsaraykin.aichat.harness.domain.*;
import com.github.vladsaraykin.aichat.rag.domain.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;

class HarnessConversationTest {
    @TempDir Path directory;
    private static final String QUOTE="Человек утверждает требования и контролирует переходы между этапами разработки.";
    private static final RagQuestion.Source SOURCE=new RagQuestion.Source(1,new DocumentChunk(UUID.randomUUID(),0,
            "test.pdf","Документ","Контроль",1,1,0,QUOTE.length(),QUOTE,20),.9);

    @Test void twoTwelveTurnScenariosRetainTaskAndValidateSourcesOnEveryAnswer() throws Exception {
        for(String goal:List.of("Сервис уведомлений","Процесс разработки")) {
            var prompts=new ArrayList<String>();
            var registry=new AgentRegistry((definition,messages)-> {
                String prompt=definition.systemPrompt();
                if(prompt.equals(definition.lifecycle().guardPrompt())) return reply("{\"result\":\"ALLOW\",\"violation\":null}");
                if(prompt.equals(definition.memoryLayers().questionsPrompt())) return reply("{\"questions\":[]}");
                if(prompt.equals(definition.memoryLayers().systemPrompt())) return reply("""
                        {"task":{"goal":"%s","requirements":{"channel":"email"},"constraints":{"stack":"Java"},
                        "decisions":{},"openQuestions":[],"terms":{"PDLC":"Жизненный цикл разработки"}},"proposals":[]}
                        """.formatted(goal));
                prompts.add(prompt);
                return reply("{\"status\":\"ANSWERED\",\"answer\":\"Контролируйте переходы [1].\",\"quotes\":[{\"sourceNumber\":1,\"quote\":\""+QUOTE+"\"}]}");
            },"classpath:agents/*.yaml");
            var service=new ChatService(registry,new FileChatRepository(directory.resolve(goal).toString()),
                    new FileLongTermMemoryRepository(directory.resolve(goal+"-memory").toString()));
            {
                var chat=service.create("architect",ContextStrategyType.SLIDING_WINDOW);
                for(int turn=0;turn<12;turn++) {
                    UUID request=UUID.randomUUID();String text="Уточнение "+turn;
                    var context=new RequestContext(ChatRepository.LEGACY_OWNER,"architect",chat.id(),request,text,
                            new ChatSettings(0,true,List.of(),null),List.of(SOURCE),null,null);
                    var events=service.stream(ChatRepository.LEGACY_OWNER,"architect",chat.id(),request,text,List.of(),context).collectList().block();
                    chat=events.getLast().chat();
                    var answer=chat.messages().getLast();
                    assertThat(answer.content()).contains("[1]");
                    assertThat(answer.evidence().grounding().quotes()).containsExactly(new RagQuestion.Evidence(1,QUOTE));
                    assertThat(chat.workingMemory().goal()).isEqualTo(goal);
                    assertThat(chat.workingMemory().terms()).containsEntry("PDLC","Жизненный цикл разработки");
                    assertThat(prompts.getLast()).contains(goal,"Java","Жизненный цикл разработки",QUOTE);
                }
                assertThat(chat.messages()).hasSize(24);
                assertThat(prompts).hasSize(12);
            }
        }
    }

    @Test void emptyRagContextSkipsPrimaryGenerationAndInvalidCitationsNeverStream() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        ConversationModel model=new ConversationModel() {
            public Reply reply(AgentDefinition definition,List<ChatMessage> messages) { calls.incrementAndGet();return replyInvalid(); }
            public Flux<StreamPart> stream(AgentDefinition d,ContextSummary s,List<ChatMessage> messages,List<String> tools,RequestContext request) {
                calls.incrementAndGet();return Flux.just(StreamPart.delta("Непроверенный JSON"),StreamPart.completed(replyInvalid()));
            }
        };
        var definition=new AgentRegistry(model,"classpath:agents/*.yaml").get("assistant").definition();
        var user=new ChatMessage(UUID.randomUUID(),ChatMessage.Role.USER,"Вопрос",java.time.Instant.now(),null);
        var context=new RequestContext("test","assistant",UUID.randomUUID(),user.id(),user.content(),new ChatSettings(0,true,List.of(),null),List.of(),null,null);
        var unknown=new ConfiguredAgent(definition,model).withRequestContext(context).answerStream(null,List.of(),user).collectList().block();
        assertThat(calls).hasValue(0);
        assertThat(unknown.getLast().completed().content()).startsWith("Не знаю:");
        var withSource=new RequestContext(context.owner(),context.agentId(),context.chatId(),context.messageId(),context.content(),context.settings(),List.of(SOURCE),null,null);
        var parts=new ArrayList<Agent.AnswerPart>();
        assertThatThrownBy(()->new ConfiguredAgent(definition,model).withRequestContext(withSource).answerStream(null,List.of(),user)
                .doOnNext(parts::add).collectList().block()).isInstanceOf(ChatFailure.class);
        assertThat(parts).isEmpty();
    }
    @Test void invalidGroundedAnswerGetsOneValidatedRepairWithoutLeakingRejectedText() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        ConversationModel model=new ConversationModel() {
            public Reply reply(AgentDefinition definition,List<ChatMessage> messages) { return replyInvalid(); }
            public Flux<StreamPart> stream(AgentDefinition d,ContextSummary s,List<ChatMessage> messages,List<String> tools,RequestContext request) {
                if(calls.getAndIncrement()==0) return Flux.just(StreamPart.delta("Непроверенный JSON"),StreamPart.completed(replyInvalid()));
                return Flux.just(StreamPart.completed(HarnessConversationTest.reply("{\"status\":\"ANSWERED\",\"answer\":\"Контролируйте переходы [1].\","
                        +"\"quotes\":[{\"sourceNumber\":1,\"quote\":\""+QUOTE+"\"}]}")));
            }
        };
        var definition=new AgentRegistry(model,"classpath:agents/*.yaml").get("assistant").definition();
        var user=new ChatMessage(UUID.randomUUID(),ChatMessage.Role.USER,"Вопрос",java.time.Instant.now(),null);
        var context=new RequestContext("test","assistant",UUID.randomUUID(),user.id(),user.content(),
                new ChatSettings(0,true,List.of(),null),List.of(SOURCE),null,null);
        var parts=new ConfiguredAgent(definition,model).withRequestContext(context).answerStream(null,List.of(),user)
                .collectList().block();
        assertThat(calls).hasValue(2);
        assertThat(parts).hasSize(1);
        assertThat(parts.getFirst().completed().content()).isEqualTo("Контролируйте переходы [1].");
        assertThat(parts.getFirst().completed().evidence().grounding().quotes())
                .containsExactly(new RagQuestion.Evidence(1,QUOTE));
    }
    private static ConversationModel.Reply replyInvalid() { return reply("{\"status\":\"ANSWERED\",\"answer\":\"Неверная ссылка [99]\",\"quotes\":[{\"sourceNumber\":99,\"quote\":\"Несуществующая цитата\"}]}"); }
    private static ConversationModel.Reply reply(String text) {
        return new ConversationModel.Reply(text,new ChatMessage.Metrics("fixture",1,1,0,0,10,0,5,15,
                BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"stop"));
    }
}
