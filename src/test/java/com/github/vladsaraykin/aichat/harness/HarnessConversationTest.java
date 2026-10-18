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
    @Test void filesModeAddsLiveWorkspaceProtocolWithoutRagJsonContract() throws Exception {
        var prompt=new java.util.concurrent.atomic.AtomicReference<String>();
        ConversationModel model=new ConversationModel() {
            public Reply reply(AgentDefinition definition,List<ChatMessage> messages) { return HarnessConversationTest.reply("План"); }
            public Flux<StreamPart> stream(AgentDefinition definition,ContextSummary summary,List<ChatMessage> messages,
                    List<String> servers,RequestContext request) {
                prompt.set(definition.systemPrompt());
                return Flux.just(StreamPart.completed(HarnessConversationTest.reply("Согласуйте план поиска API.")));
            }
        };
        var definition=new AgentRegistry(model,"classpath:agents/*.yaml").get("assistant").definition();
        var user=new ChatMessage(UUID.randomUUID(),ChatMessage.Role.USER,"Приступай",java.time.Instant.now(),null);
        var context=new RequestContext("test","assistant",UUID.randomUUID(),user.id(),user.content(),
                new ChatSettings(0,false,List.of("project-files"),null),null,null,null,false,null,false,true);
        var parts=new ConfiguredAgent(definition,model).withRequestContext(context)
                .answerStream(null,List.of(),user).collectList().block();
        assertThat(prompt.get()).contains("Режим /files", "project_preview_document", "expectedSha256", "Planning/Done",
                        "Если цель ясна", "Каждый вызов требует разрешения UI")
                .doesNotContain("Вначале согласуй цель и план", "выполняй инструменты только на Execution/Validation")
                .doesNotContain(com.github.vladsaraykin.aichat.rag.application.GroundedAnswerValidator.INSTRUCTION);
        assertThat(parts.getLast().completed().content()).contains("Согласуйте план");
    }
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
        var safe= new ConfiguredAgent(definition,model).withRequestContext(withSource).answerStream(null,List.of(),user)
                .doOnNext(parts::add).collectList().block();
        assertThat(parts).hasSize(1);
        assertThat(safe.getFirst().completed().content()).contains("Неподтверждённый текст скрыт")
                .doesNotContain("Неверная ссылка");
        assertThat(safe.getFirst().completed().evidence().grounding().reason()).isEqualTo("ANSWER_NOT_VERIFIABLE");
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

    @Test void invalidRepairReturnsSafeInsufficientContextInsteadOfFailingTheChat() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        ConversationModel model=new ConversationModel() {
            public Reply reply(AgentDefinition definition,List<ChatMessage> messages) { return replyInvalid(); }
            public Flux<StreamPart> stream(AgentDefinition d,ContextSummary s,List<ChatMessage> messages,List<String> tools,RequestContext request) {
                calls.incrementAndGet();
                return Flux.just(StreamPart.completed(replyInvalid()));
            }
        };
        var definition=new AgentRegistry(model,"classpath:agents/*.yaml").get("assistant").definition();
        var user=new ChatMessage(UUID.randomUUID(),ChatMessage.Role.USER,"Вопрос",java.time.Instant.now(),null);
        var context=new RequestContext("test","assistant",UUID.randomUUID(),user.id(),user.content(),
                new ChatSettings(0,true,List.of(),null),List.of(SOURCE),null,null);

        var answer=new ConfiguredAgent(definition,model).withRequestContext(context)
                .answerStream(null,List.of(),user).collectList().block().getFirst().completed();

        assertThat(calls).hasValue(2);
        assertThat(answer.content()).contains("Не удалось проверить ответ", "Неподтверждённый текст скрыт")
                .doesNotContain("Неверная ссылка");
        assertThat(answer.evidence().sources()).containsExactly(SOURCE);
        assertThat(answer.evidence().grounding().reason()).isEqualTo("ANSWER_NOT_VERIFIABLE");
    }

    @Test void projectHelpRemovesCommandPrefixAndUsesValidatedRagAnswerWithoutTaskExtraction() throws Exception {
        var seenPrompt=new java.util.concurrent.atomic.AtomicReference<String>();
        var seenQuestion=new java.util.concurrent.atomic.AtomicReference<String>();
        ConversationModel model=new ConversationModel() {
            public Reply reply(AgentDefinition definition,List<ChatMessage> messages) { return replyInvalid(); }
            public Flux<StreamPart> stream(AgentDefinition definition,ContextSummary summary,List<ChatMessage> messages,
                    List<String> servers,com.github.vladsaraykin.aichat.harness.domain.RequestContext request) {
                seenPrompt.set(definition.systemPrompt());seenQuestion.set(messages.getLast().content());
                return Flux.just(StreamPart.completed(HarnessConversationTest.reply("{\"status\":\"ANSWERED\",\"answer\":\"Описание проекта [1].\","
                        +"\"quotes\":[{\"sourceNumber\":1,\"quote\":\""+QUOTE+"\"}]}")));
            }
        };
        var definition=new AgentRegistry(model,"classpath:agents/*.yaml").get("assistant").definition();
        var original=new ChatMessage(UUID.randomUUID(),ChatMessage.Role.USER,"/help Где описан процесс?",java.time.Instant.now(),null);
        var context=new RequestContext("test","assistant",UUID.randomUUID(),original.id(),original.content(),
                new ChatSettings(0,false,List.of(),null),List.of(SOURCE),null,null,true,"Где описан процесс?");
        var chat=Chat.create("assistant");

        var answer=new ConfiguredAgent(definition,model).withRequestContext(context)
                .answerStream(chat,original,List.of(),com.github.vladsaraykin.aichat.user.domain.UserProfile.initial("test"),List.of())
                .collectList().block().getFirst().completed();

        assertThat(seenQuestion).hasValue("Где описан процесс?");
        assertThat(seenPrompt.get()).contains("режиме /help","фрагменты документации проекта");
        assertThat(answer.content()).isEqualTo("Описание проекта [1].");
        assertThat(answer.evidence().grounding().status()).isEqualTo("VERIFIED");
    }

    @Test void mcpOnlyGitRequestSkipsRagValidationAndRequiresActualToolResult() throws Exception {
        var seenPrompt=new java.util.concurrent.atomic.AtomicReference<String>();
        var seenMessages=new java.util.concurrent.atomic.AtomicReference<List<ChatMessage>>();
        var gitSource=new RagQuestion.Source(1,new DocumentChunk(UUID.randomUUID(),1,
                "MCP/project-git/get_current_branch","get_current_branch","MCP result",null,null,
                0,4,"main",0),1);
        ConversationModel model=new ConversationModel() {
            public Reply reply(AgentDefinition definition,List<ChatMessage> messages) { return replyInvalid(); }
            public Flux<StreamPart> stream(AgentDefinition definition,ContextSummary summary,List<ChatMessage> messages,
                    List<String> servers,RequestContext request) {
                seenPrompt.set(definition.systemPrompt());seenMessages.set(messages);
                return Flux.just(StreamPart.delta("Current branch: main"),
                        StreamPart.completed(HarnessConversationTest.reply("Current branch: main")));
            }
            public List<RagQuestion.Source> toolSources(RequestContext request) { return List.of(gitSource); }
        };
        var definition=new AgentRegistry(model,"classpath:agents/*.yaml").get("assistant").definition();
        var chat=Chat.create("assistant");
        var user=new ChatMessage(UUID.randomUUID(),ChatMessage.Role.USER,"Получи текущую git-ветку",java.time.Instant.now(),null);
        var context=new RequestContext("test","assistant",chat.id(),user.id(),user.content(),
                new ChatSettings(0,true,List.of("project-git"),null),null,null,null,false,null,true);

        var parts=new ConfiguredAgent(definition,model).withRequestContext(context)
                .answerStream(chat,user,List.of(),com.github.vladsaraykin.aichat.user.domain.UserProfile.initial("test"),List.of("project-git"))
                .collectList().block();

        assertThat(seenPrompt.get()).contains("MCP-only","project-git/get_current_branch")
                .doesNotContain("цитируй подтверждённые сведения");
        assertThat(seenMessages.get()).extracting(ChatMessage::content).containsExactly("Получи текущую git-ветку");
        assertThat(parts).hasSize(1); // Hold back streamed claims until the tool result has been verified.
        assertThat(parts.getFirst().completed().content()).isEqualTo("Current branch: main");
        assertThat(parts.getFirst().completed().evidence().sources()).containsExactly(gitSource);
        assertThat(parts.getFirst().completed().evidence().grounding()).isNull();
    }

    @Test void mcpOnlyGitRequestCannotClaimAnAnswerWithoutToolOutput() throws Exception {
        ConversationModel model=new ConversationModel() {
            public Reply reply(AgentDefinition definition,List<ChatMessage> messages) { return replyInvalid(); }
            public Flux<StreamPart> stream(AgentDefinition definition,ContextSummary summary,List<ChatMessage> messages,
                    List<String> servers,RequestContext request) {
                return Flux.just(StreamPart.completed(HarnessConversationTest.reply("Ветка main")));
            }
        };
        var definition=new AgentRegistry(model,"classpath:agents/*.yaml").get("assistant").definition();
        var chat=Chat.create("assistant");
        var user=new ChatMessage(UUID.randomUUID(),ChatMessage.Role.USER,"Получи текущую git-ветку",java.time.Instant.now(),null);
        var context=new RequestContext("test","assistant",chat.id(),user.id(),user.content(),
                new ChatSettings(0,true,List.of("project-git"),null),null,null,null,false,null,true);

        assertThatThrownBy(()->new ConfiguredAgent(definition,model).withRequestContext(context)
                .answerStream(chat,user,List.of(),com.github.vladsaraykin.aichat.user.domain.UserProfile.initial("test"),List.of("project-git"))
                .collectList().block())
                .hasMessageContaining("Git MCP не вернул результат");
    }

    @Test void projectHelpInLayeredChatDoesNotRunMemoryExtractionOrChangeTaskState() throws Exception {
        var questionCalls=new java.util.concurrent.atomic.AtomicInteger();
        var primaryQuestion=new java.util.concurrent.atomic.AtomicReference<String>();
        ConversationModel model=new ConversationModel() {
            public Reply reply(AgentDefinition definition,List<ChatMessage> messages) { return HarnessConversationTest.reply("unused"); }
            public Flux<StreamPart> stream(AgentDefinition definition,ContextSummary summary,List<ChatMessage> messages,
                    List<String> servers,com.github.vladsaraykin.aichat.harness.domain.RequestContext request) {
                if(definition.systemPrompt().equals(definition.memoryLayers().questionsPrompt())) {
                    questionCalls.incrementAndGet();return Flux.just(StreamPart.completed(HarnessConversationTest.reply("{\"questions\":[]}")));
                }
                primaryQuestion.set(messages.getLast().content());
                return Flux.just(StreamPart.completed(HarnessConversationTest.reply("{\"status\":\"ANSWERED\",\"answer\":\"Описание проекта [1].\","
                        +"\"quotes\":[{\"sourceNumber\":1,\"quote\":\""+QUOTE+"\"}]}")));
            }
        };
        var chats=new ChatService(new AgentRegistry(model,"classpath:agents/*.yaml"),
                new FileChatRepository(directory.resolve("project-help-chat").toString()),
                new FileLongTermMemoryRepository(directory.resolve("project-help-memory").toString()));
        var chat=chats.create("test","assistant",ContextStrategyType.SLIDING_WINDOW);
        chat=chats.editTask("test","assistant",chat.id(),0,"project",new MemoryService.TaskData(
                "Разобраться в проекте",Map.of(),Map.of(),Map.of(),List.of()));
        var original=new ChatMessage(UUID.randomUUID(),ChatMessage.Role.USER,"/help Где описан процесс?",java.time.Instant.now(),null);
        var context=new RequestContext("test","assistant",chat.id(),original.id(),original.content(),
                new ChatSettings(0,false,List.of(),null),List.of(SOURCE),null,null,true,"Где описан процесс?");

        var events=chats.stream("test","assistant",chat.id(),original.id(),original.content(),List.of(),context).collectList().block();
        var completed=events.getLast().chat();

        assertThat(primaryQuestion).hasValue("Где описан процесс?");
        assertThat(questionCalls).hasValue(0);
        assertThat(completed.messages().get(completed.messages().size()-2).content()).isEqualTo(original.content());
        assertThat(completed.workingMemory().goal()).isEqualTo("Разобраться в проекте");
        assertThat(completed.workingMemory().version()).isEqualTo(chat.workingMemory().version());
    }

    private static ConversationModel.Reply replyInvalid() { return reply("{\"status\":\"ANSWERED\",\"answer\":\"Неверная ссылка [99]\",\"quotes\":[{\"sourceNumber\":99,\"quote\":\"Несуществующая цитата\"}]}"); }
    private static ConversationModel.Reply reply(String text) {
        return new ConversationModel.Reply(text,new ChatMessage.Metrics("fixture",1,1,0,0,10,0,5,15,
                BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"stop"));
    }
}
