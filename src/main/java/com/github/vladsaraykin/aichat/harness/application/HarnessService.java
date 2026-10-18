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
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(HarnessService.class);
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
        var prepared=new java.util.concurrent.atomic.AtomicBoolean();
        long started=System.nanoTime();
        return Flux.concat(Flux.just(new Event("retrieving",Map.of("messageId",messageId))),
                Mono.fromCallable(()->prepare(owner,agent,chatId,messageId,content,acquired)).subscribeOn(workers)
                        .doOnNext(context->{
                            prepared.set(true);
                            log.info("harness_request_prepared agentId={} chatId={} requestId={} mode={} ragEnabled={} mcpServers={}",
                                    agent,chatId,messageId,context.fileOperations() ? "files" : context.projectHelp() ? "help" : "chat",
                                    context.ragEnabled(),context.settings().mcpServerIds());
                        })
                        .flatMapMany(context->Flux.concat(Flux.just(new Event("request_context",Map.of(
                                "mode",context.fileOperations() ? "FILES" : "CHAT","ragEnabled",context.ragEnabled()))),
                                chats.stream(owner,agent,chatId,messageId,content,context.settings().mcpServerIds(),context)
                                .map(e->{
                                    if(e.type()==ChatService.StreamEvent.Type.COMPLETED) {
                                        if(acquired.get()) requests.status(owner,messageId,"COMPLETED");
                                        finished.set(true);
                                    }
                                    return new Event(e.type().name().toLowerCase(Locale.ROOT),e);
                                }))))
                .onErrorResume(McpApprovalService.Required.class,error->{
                    log.info("harness_request_awaiting_approval agentId={} chatId={} requestId={} server={} tool={}",
                            agent,chatId,messageId,error.call().server(),error.call().tool());
                    requests.status(owner,messageId,"AWAITING_APPROVAL");
                    finished.set(true);
                    return Flux.just(new Event("approval",error.call()));
                }).onErrorResume(error->{
                    log.warn("harness_request_failed agentId={} chatId={} requestId={} phase={} errorType={} errorKind={}",
                            agent,chatId,messageId,prepared.get() ? "generation" : "preparation",
                            error.getClass().getSimpleName(),error instanceof ChatFailure failure ? failure.kind() : "INTERNAL");
                    if(acquired.get()) {
                        requests.status(owner,messageId,"FAILED");
                        requests.find(owner,messageId).ifPresent(r->requests.incomplete(r.context()));
                    }
                    finished.set(true);
                    String message=error instanceof ChatFailure ? error.getMessage() : "Запрос не завершён. Проверьте подключения и повторите отправку.";
                    return Flux.just(new Event("error",Map.of("message",message)));
                }).doOnSubscribe(subscription->log.info(
                        "harness_request_received agentId={} chatId={} requestId={} mode={} promptLength={}",
                        agent,chatId,messageId,ProjectFilesCommand.matches(content) ? "files"
                                : ProjectHelpCommand.parse(content).isPresent() ? "help" : "chat",content.length()))
                .doFinally(signal->{
                    log.info("harness_request_finished agentId={} chatId={} requestId={} signal={} durationMs={}",
                            agent,chatId,messageId,signal,(System.nanoTime()-started)/1_000_000);
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
                context=rag.prepare(context,chat,requestDefinition(agent,context.settings()));
                requests.context(context);
            }
            return context;
        }
        var preferences=settings.get(owner,agent,chatId);
        var help=ProjectHelpCommand.parse(content);
        boolean explicitFiles=ProjectFilesCommand.matches(content);
        boolean gitBranch=!explicitFiles && ProjectGitCommand.requestsCurrentBranch(content,Set.copyOf(preferences.mcpServerIds()));
        boolean previousFiles=!gitBranch && preferences.mcpServerIds().contains("project-files")
                && requests.latestCompleted(owner,agent,chatId).map(value->value.context().fileOperations()).orElse(false);
        boolean files=!gitBranch && ProjectFilesCommand.continues(content,previousFiles);
        if(files) {
            if(!preferences.mcpServerIds().contains("project-files"))
                log.warn("harness_request_rejected agentId={} chatId={} requestId={} reason=project_files_not_selected mcpServers={}",
                        agent,chatId,id,preferences.mcpServerIds());
            preferences=ProjectFilesCommand.snapshot(preferences);
        }
        String question=help.map(ProjectHelpCommand::question).orElse(null);
        if(ProjectFilesCommand.exits(content)) {
            question=content.substring(5).strip();
            if(question.isEmpty()) question="Продолжим диалог с обычными настройками чата.";
        }
        var initial=new RequestContext(owner,agent,chatId,id,content,preferences,
                !files && !gitBranch && (help.isPresent() || preferences.ragEnabled()) ? List.of() : null,null,null,
                help.isPresent(),question,gitBranch,files);
        requests.create(initial);
        acquired.set(true);
        var prepared=rag.prepare(initial,chat,requestDefinition(agent,preferences));
        requests.context(prepared);
        return prepared;
    }
    private AgentDefinition requestDefinition(String agentId, ChatSettings selected) {
        var definition=agents.get(agentId).definition();
        return selected.model()==null ? definition : definition.withModel(selected.model(),selected.reasoningEffort());
    }
    @jakarta.annotation.PreDestroy public void close() { workers.dispose(); }
}
