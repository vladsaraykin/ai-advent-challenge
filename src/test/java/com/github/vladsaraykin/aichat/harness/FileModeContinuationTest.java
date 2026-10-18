package com.github.vladsaraykin.aichat.harness;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.agent.infrastructure.AgentRegistry;
import com.github.vladsaraykin.aichat.harness.application.*;
import com.github.vladsaraykin.aichat.harness.domain.*;
import com.github.vladsaraykin.aichat.harness.infrastructure.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileModeContinuationTest {
    @Test void unprefixedFollowupInheritsFilesModeWithoutChangingStoredRagOrOtherServers() throws Exception {
        try(var f=new Fixture()) {
            var previous=f.context("/files Найди реализацию",true);
            when(f.requests.latestCompleted("alice","assistant",f.chat.id())).thenReturn(Optional.of(f.view(previous,"COMPLETED")));
            f.send("Приступай");
            assertThat(f.prepared.get().fileOperations()).isTrue();
            assertThat(f.prepared.get().projectFiles()).isTrue();
            assertThat(f.prepared.get().ragEnabled()).isFalse();
            assertThat(f.prepared.get().settings().mcpServerIds()).containsExactly("project-files");
            assertThat(f.preferences.ragEnabled()).isTrue();
            verify(f.settings,never()).save(anyString(),anyString(),any(),any());
            verify(f.requests).latestCompleted("alice","assistant",f.chat.id());
        }
    }
    @Test void newChatDoesNotInheritModeJustBecauseAFileServerIsSelected() throws Exception {
        try(var f=new Fixture()) {
            f.send("Найди описание API");
            assertThat(f.prepared.get().fileOperations()).isFalse();
            assertThat(f.prepared.get().ragEnabled()).isTrue();
        }
    }
    @Test void explicitExitAndHelpRestoreNormalRetrieval() throws Exception {
        for(var command:List.of("/chat Сколько документов?","/help Опиши API")) {
            try(var f=new Fixture()) {
                when(f.requests.latestCompleted("alice","assistant",f.chat.id())).thenReturn(Optional.of(f.view(f.context("Приступай",true),"COMPLETED")));
                f.send(command);
                assertThat(f.prepared.get().fileOperations()).isFalse();
                assertThat(f.prepared.get().ragEnabled()).isTrue();
                assertThat(f.prepared.get().promptQuestion()).doesNotContain("/chat", "/help");
            }
        }
    }
    @Test void retryRetainsOriginalRagSnapshotAndDoesNotRerouteOrCreateANewRequest() throws Exception {
        try(var f=new Fixture()) {
            var old=f.context("Приступай",false);
            when(f.requests.find("alice",old.messageId())).thenReturn(Optional.of(f.view(old,"FAILED")));
            when(f.requests.claim("alice",old.messageId())).thenReturn(true);
            f.service.stream("alice","assistant",f.chat.id(),old.messageId(),old.content()).collectList().block();
            assertThat(f.prepared.get()).isSameAs(old);
            assertThat(f.prepared.get().ragEnabled()).isTrue();
            verify(f.requests,never()).latestCompleted(anyString(),anyString(),any());
            verify(f.requests,never()).create(any());
        }
    }
    @Test void requestModeRoundTripsAndOldSlashSnapshotsAreReadable() {
        var context=new RequestContext("alice","assistant",UUID.randomUUID(),UUID.randomUUID(),"Приступай",
                new ChatSettings(0,false,List.of("project-files"),null),null,null,null,false,null,false,true);
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(json.readValue(json.writeValueAsString(context),RequestContext.class).fileOperations()).isTrue();
        var slash=new RequestContext("alice","assistant",context.chatId(),UUID.randomUUID(),"/files Найди API",context.settings(),null,null,null);
        String old=json.writeValueAsString(slash).replace(",\"projectFiles\":false","");
        assertThat(json.readValue(old,RequestContext.class).fileOperations()).isTrue();
        assertThat(ProjectFilesCommand.continues("Приступай",true)).isTrue();
        assertThat(ProjectFilesCommand.continues("/chat",true)).isFalse();
        assertThat(ProjectFilesCommand.continues("/helper",true)).isTrue();
        var approval=json.readValue("{\"callId\":\""+UUID.randomUUID()+"\",\"allow\":true}",
                com.github.vladsaraykin.aichat.harness.api.HarnessController.Approval.class);
        assertThat(approval.remember()).isFalse();
    }
    @Test void completedReplayDoesNotMoveThePersistedModeByUpdatingAnOldTimestamp() throws Exception {
        try(var f=new Fixture()) {
            var old=f.context("Приступай",true);
            when(f.requests.find("alice",old.messageId())).thenReturn(Optional.of(f.view(old,"COMPLETED")));
            f.service.stream("alice","assistant",f.chat.id(),old.messageId(),old.content()).collectList().block();
            verify(f.requests,never()).status("alice",old.messageId(),"COMPLETED");
            verify(f.requests,never()).latestCompleted(anyString(),anyString(),any());
        }
    }
    static class Fixture implements AutoCloseable {
        final Chat chat=Chat.create("assistant");
        final ChatService chats=mock(ChatService.class);
        final ChatSettingsRepository settings=mock(ChatSettingsRepository.class);
        final HarnessRequestRepository requests=mock(HarnessRequestRepository.class);
        final RagContextService rag=mock(RagContextService.class);
        final ChatSettings preferences=new ChatSettings(0,true,List.of("project-files","excel"),null);
        final AtomicReference<RequestContext> prepared=new AtomicReference<>();
        final HarnessService service;
        Fixture() throws Exception {
            when(chats.get("alice","assistant",chat.id())).thenReturn(chat);
            when(settings.get("alice","assistant",chat.id())).thenReturn(preferences);
            when(requests.latestCompleted(anyString(),anyString(),any())).thenReturn(Optional.empty());
            when(rag.prepare(any(),any(),any())).thenAnswer(inv->inv.getArgument(0));
            when(chats.stream(anyString(),anyString(),any(),any(),anyString(),anyList(),any())).thenAnswer(inv->{
                prepared.set(inv.getArgument(6));
                return Flux.just(new ChatService.StreamEvent(ChatService.StreamEvent.Type.COMPLETED,null,null,chat,null));
            });
            service=new HarnessService(chats,new AgentRegistry((d,m)->null,"classpath:agents/*.yaml"),settings,requests,rag);
        }
        RequestContext context(String text,boolean files) {
            return new RequestContext("alice","assistant",chat.id(),UUID.randomUUID(),text,
                    files ? ProjectFilesCommand.snapshot(preferences) : preferences,files ? null : List.of(),null,null,false,null,false,files);
        }
        HarnessRequestRepository.RequestView view(RequestContext context,String status) {
            return new HarnessRequestRepository.RequestView(context,status,List.of(),true);
        }
        void send(String text) {
            var events=service.stream("alice","assistant",chat.id(),UUID.randomUUID(),text).collectList().block();
            assertThat(events).noneMatch(event->event.type().equals("error"));
            assertThat(events).anyMatch(event->event.type().equals("request_context"));
        }
        public void close() { service.close(); }
    }
}
