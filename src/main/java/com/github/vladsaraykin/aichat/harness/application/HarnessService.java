package com.github.vladsaraykin.aichat.harness.application;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.harness.domain.*;
import com.github.vladsaraykin.aichat.harness.infrastructure.*;
import com.github.vladsaraykin.aichat.mcp.application.McpApprovalService;
import java.util.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class HarnessService {
    private final ChatService chats;
    private final AgentCatalog agents;
    private final ChatSettingsRepository settings;
    private final HarnessRequestRepository requests;
    private final RagContextService rag;
    private final reactor.core.scheduler.Scheduler workers=Schedulers.newBoundedElastic(4,16,"harness");
    public HarnessService(ChatService chats,AgentCatalog agents,ChatSettingsRepository settings,
                          HarnessRequestRepository requests,RagContextService rag) {
        this.chats=chats;this.agents=agents;this.settings=settings;this.requests=requests;this.rag=rag;
    }
    public record Event(String type,Object data) { }
    public Flux<Event> stream(String owner,String agent,UUID chatId,UUID messageId,String content) {
        var acquired=new java.util.concurrent.atomic.AtomicBoolean();
        var finished=new java.util.concurrent.atomic.AtomicBoolean();
        return Flux.concat(Flux.just(new Event("retrieving",Map.of("messageId",messageId))),
                Mono.fromCallable(()->prepare(owner,agent,chatId,messageId,content,acquired)).subscribeOn(workers)
                        .flatMapMany(context->chats.stream(owner,agent,chatId,messageId,content,context.settings().mcpServerIds(),context)
                                .map(e->{
                                    if(e.type()==ChatService.StreamEvent.Type.COMPLETED) { requests.status(owner,messageId,"COMPLETED");finished.set(true); }
                                    return new Event(e.type().name().toLowerCase(Locale.ROOT),e);
                                })))
                .onErrorResume(McpApprovalService.Required.class,error->{
                    requests.status(owner,messageId,"AWAITING_APPROVAL");
                    finished.set(true);
                    return Flux.just(new Event("approval",error.call()));
                }).onErrorResume(error->{
                    if(acquired.get()) {
                        requests.status(owner,messageId,"FAILED");
                        requests.find(owner,messageId).ifPresent(r->requests.incomplete(r.context()));
                    }
                    finished.set(true);
                    String message=error instanceof ChatFailure ? error.getMessage() : "Запрос не завершён. Проверьте подключения и повторите отправку.";
                    return Flux.just(new Event("error",Map.of("message",message)));
                }).doFinally(signal->{
                    if(acquired.get() && !finished.get()) {
                        requests.status(owner,messageId,"INTERRUPTED");
                        requests.find(owner,messageId).ifPresent(r->requests.incomplete(r.context()));
                    }
                });
    }
    private RequestContext prepare(String owner,String agent,UUID chatId,UUID id,String content,java.util.concurrent.atomic.AtomicBoolean acquired) {
        var chat=chats.get(owner,agent,chatId);
        if(chat.readOnly()) throw new ChatFailure(ChatFailure.Kind.INVALID,"Выберите ветку для продолжения.");
        if(chat.workingMemory().status()==WorkingMemory.Status.PAUSED) throw new ChatFailure(ChatFailure.Kind.INVALID,"Задача на паузе. Продолжите её в панели памяти.");
        var old=requests.find(owner,id);
        if(old.isPresent()) {
            var context=old.get().context();
            if(!context.chatId().equals(chatId) || !context.agentId().equals(agent) || !context.content().equals(content))
                throw new ChatFailure(ChatFailure.Kind.INVALID,"Идентификатор запроса уже использован для другого сообщения.");
            if(!old.get().status().equals("COMPLETED") && !requests.claim(owner,id))
                throw new ChatFailure(ChatFailure.Kind.BUSY,"Запрос уже выполняется.");
            acquired.set(!old.get().status().equals("COMPLETED"));
            if(context.ragEnabled() && context.retrieval()==null) {
                context=rag.prepare(context,chat,agents.get(agent).definition());
                requests.context(context);
            }
            return context;
        }
        var preferences=settings.get(owner,agent,chatId);
        var initial=new RequestContext(owner,agent,chatId,id,content,preferences,preferences.ragEnabled() ? List.of() : null,null,null);
        requests.create(initial);
        acquired.set(true);
        var prepared=rag.prepare(initial,chat,agents.get(agent).definition());
        requests.context(prepared);
        return prepared;
    }
    @jakarta.annotation.PreDestroy public void close() { workers.dispose(); }
}
