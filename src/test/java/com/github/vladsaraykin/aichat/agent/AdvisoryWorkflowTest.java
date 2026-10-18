package com.github.vladsaraykin.aichat.agent;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.agent.infrastructure.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;

class AdvisoryWorkflowTest {
    @TempDir Path directory;

    @Test void fileGoalRunsFromPlanningAndDoneWithEmptyFieldsAndUnansweredQuestions() throws Exception {
        var calls=new AtomicInteger();
        ConversationModel model=new ConversationModel() {
            public Reply reply(AgentDefinition d,List<ChatMessage> messages) {
                return MemoryLayersTest.reply("{\"task\":{\"goal\":\"Документация аутентификации\",\"requirements\":{},\"constraints\":{},\"decisions\":{},\"openQuestions\":[],\"terms\":{}},\"proposals\":[]}");
            }
            public Flux<StreamPart> stream(AgentDefinition d,ContextSummary summary,List<ChatMessage> messages) {
                calls.incrementAndGet();
                assertThat(d.systemPrompt()).contains("мягкий (advisory)", "Уточняющие вопросы задавай прямо");
                return Flux.just(StreamPart.completed(MemoryLayersTest.reply("Какую версию API описать?")));
            }
            public Flux<StreamPart> checkLifecycle(AgentDefinition d,List<ChatMessage> messages) {
                throw new AssertionError("Advisory workflow must not invoke a blocking lifecycle guard");
            }
            public Flux<StreamPart> extractQuestions(AgentDefinition d,List<ChatMessage> messages) {
                // A broken optional helper must not discard an otherwise successful answer.
                return Flux.just(StreamPart.completed(MemoryLayersTest.reply("invalid-json")));
            }
        };
        var registry=new AgentRegistry(model,"classpath:agents/*.yaml");
        var repository=new FileChatRepository(directory.resolve("chats").toString());
        var service=new ChatService(registry,repository,new FileLongTermMemoryRepository(directory.resolve("memory").toString()));
        for(var stage:List.of(WorkingMemory.Stage.REQUIREMENTS,WorkingMemory.Stage.DONE)) {
            var chat=service.create("assistant",ContextStrategyType.SLIDING_WINDOW);
            repository.save(chat.withWorkingMemory(new WorkingMemory(1,stage,"","Документация",
                    Map.of(),Map.of(),Map.of(),List.of("Какую версию описать?"),List.of(),null,null,
                    WorkingMemory.Status.ACTIVE,null,null)));
            var events=service.stream("assistant",chat.id(),UUID.randomUUID(),
                    "/files Изучи аутентификацию API, Controller, Service, Repository и создай project/docs/auth.md")
                    .collectList().block();
            var saved=events.getLast().chat();
            assertThat(saved.messages()).hasSize(2);
            assertThat(saved.messages().getLast().content()).isEqualTo("Какую версию API описать?");
            assertThat(saved.workingMemory().usage().calls()).isEqualTo(2);
            assertThat(saved.workingMemory().requirements()).isEmpty();
            assertThat(events).extracting(ChatService.StreamEvent::type)
                    .doesNotContain(ChatService.StreamEvent.Type.CHECKING_LIFECYCLE);
            var paused=service.pauseTask(ChatRepository.LEGACY_OWNER,"assistant",chat.id(),saved.workingMemory().version());
            assertThat(paused.workingMemory().status()).isEqualTo(WorkingMemory.Status.PAUSED);
            assertThatThrownBy(()->service.stream("assistant",chat.id(),UUID.randomUUID(),"Продолжай"))
                    .hasMessageContaining("на паузе");
        }
        assertThat(calls).hasValue(2);
    }
}
