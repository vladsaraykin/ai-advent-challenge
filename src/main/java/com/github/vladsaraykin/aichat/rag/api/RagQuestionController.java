package com.github.vladsaraykin.aichat.rag.api;

import com.github.vladsaraykin.aichat.rag.application.RagQuestionService;
import com.github.vladsaraykin.aichat.rag.domain.*;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/rag")
@ConditionalOnProperty(prefix="app.rag",name="enabled",havingValue="true")
public class RagQuestionController {
    private final RagQuestionService service;
    private final ScheduledThreadPoolExecutor heartbeats = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().name("rag-sse-heartbeat").factory());
    public RagQuestionController(RagQuestionService service) { this.service=service; heartbeats.setRemoveOnCancelPolicy(true); }
    @jakarta.annotation.PreDestroy public void close() { heartbeats.shutdownNow(); }
    @GetMapping("/answer-settings") public RagAnswerSettings settings() { return service.settings(); }
    @GetMapping("/indexes/{id}/questions") public List<RagQuestion> list(Principal principal,@PathVariable UUID id) { return service.list(principal.getName(),id); }
    @PostMapping(value="/questions/stream",produces="text/event-stream")
    public SseEmitter stream(Principal principal,@RequestBody RagQuestionService.Request request) {
        var emitter=new SseEmitter(900_000L);
        var cancelled=new AtomicBoolean();
        var heartbeat=new AtomicReference<ScheduledFuture<?>>();
        Runnable stop=() -> { cancelled.set(true); var future=heartbeat.get(); if(future!=null) future.cancel(false); };
        emitter.onCompletion(stop); emitter.onTimeout(() -> { stop.run(); emitter.complete(); });
        emitter.onError(error -> stop.run());
        service.start(principal.getName(),request,(event,data) -> {
            if (cancelled.get()) throw new IllegalStateException("Disconnected");
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
                if (event.equals("completed") || event.equals("error")) emitter.complete();
            } catch (IOException error) { cancelled.set(true); emitter.completeWithError(new IllegalStateException("Stream disconnected")); throw new IllegalStateException("Disconnected"); }
        },cancelled::get);
        heartbeat.set(heartbeats.scheduleAtFixedRate(() -> {
            if (cancelled.get()) return;
            try { emitter.send(SseEmitter.event().name("heartbeat").data(Map.of("waiting",true))); }
            catch (Exception error) { stop.run(); emitter.complete(); }
        },15,15,TimeUnit.SECONDS));
        if(cancelled.get()) heartbeat.get().cancel(false);
        return emitter;
    }
}
