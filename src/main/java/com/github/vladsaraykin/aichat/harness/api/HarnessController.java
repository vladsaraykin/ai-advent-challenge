package com.github.vladsaraykin.aichat.harness.api;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.ChatMessage;
import com.github.vladsaraykin.aichat.agent.infrastructure.JdbcChatRepository;
import com.github.vladsaraykin.aichat.harness.application.HarnessService;
import com.github.vladsaraykin.aichat.harness.domain.ChatSettings;
import com.github.vladsaraykin.aichat.harness.infrastructure.*;
import com.github.vladsaraykin.aichat.mcp.application.McpApprovalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/harness/agents/{agentId}/chats/{chatId}")
public class HarnessController {
    private final HarnessService harness;
    private final ChatService chats;
    private final JdbcChatRepository history;
    private final ChatSettingsRepository settings;
    private final HarnessRequestRepository requests;
    private final McpApprovalService approvals;
    public HarnessController(HarnessService harness,ChatService chats,JdbcChatRepository history,
            ChatSettingsRepository settings,HarnessRequestRepository requests,McpApprovalService approvals) {
        this.harness=harness;this.chats=chats;this.history=history;this.settings=settings;this.requests=requests;this.approvals=approvals;
    }
    @GetMapping("/history") public List<ChatMessage> history(Principal user,@PathVariable String agentId,@PathVariable UUID chatId) {
        return history.history(user.getName(),agentId,chatId);
    }
    @GetMapping("/usage") public List<HarnessRequestRepository.UsageView> usage(Principal user,@PathVariable String agentId,@PathVariable UUID chatId) {
        chats.get(user.getName(),agentId,chatId);
        return requests.usage(user.getName(),chatId);
    }
    @GetMapping("/settings") public ChatSettings settings(Principal user,@PathVariable String agentId,@PathVariable UUID chatId) {
        return settings.get(user.getName(),agentId,chatId);
    }
    @PutMapping("/settings") public ChatSettings settings(Principal user,@PathVariable String agentId,@PathVariable UUID chatId,@RequestBody ChatSettings value) {
        return settings.save(user.getName(),agentId,chatId,value);
    }
    public record Message(@NotNull UUID messageId,@NotBlank @Size(max=12000) String content) { }
    @GetMapping("/pending") public Map<String,Object> pending(Principal user,@PathVariable String agentId,@PathVariable UUID chatId) {
        chats.get(user.getName(),agentId,chatId);
        var request=requests.pending(user.getName(),chatId);
        if(request.isEmpty()) return Map.of();
        return Map.of("request",request.get(),"calls",approvals.calls(user.getName(),request.get().context().messageId()));
    }
    @PostMapping(value="/messages/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<Object>>> stream(Principal user,@PathVariable String agentId,@PathVariable UUID chatId,@Valid @RequestBody Message message) {
        return response(harness.stream(user.getName(),agentId,chatId,message.messageId(),message.content().strip()));
    }
    public record Approval(@NotNull UUID callId,boolean allow) { }
    @GetMapping("/requests/{requestId}") public Map<String,Object> request(Principal user,@PathVariable String agentId,@PathVariable UUID chatId,@PathVariable UUID requestId) {
        chats.get(user.getName(),agentId,chatId);
        var request=owned(user.getName(),agentId,chatId,requestId);
        return Map.of("request",request,"calls",approvals.calls(user.getName(),requestId));
    }
    @PostMapping(value="/requests/{requestId}/approval/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<Object>>> approve(Principal user,@PathVariable String agentId,@PathVariable UUID chatId,
            @PathVariable UUID requestId,@Valid @RequestBody Approval approval) {
        var request=owned(user.getName(),agentId,chatId,requestId);
        if(!request.status().equals("AWAITING_APPROVAL")) throw new ChatFailure(ChatFailure.Kind.INVALID,"Запрос не ожидает подтверждения.");
        approvals.decide(user.getName(),requestId,approval.callId(),approval.allow());
        return response(harness.stream(user.getName(),agentId,chatId,requestId,request.context().content()));
    }
    @DeleteMapping("/requests/{requestId}") public void cancel(Principal user,@PathVariable String agentId,@PathVariable UUID chatId,@PathVariable UUID requestId) {
        var request=owned(user.getName(),agentId,chatId,requestId);
        if(request.status().equals("RUNNING")) throw new ChatFailure(ChatFailure.Kind.BUSY,"Дождитесь остановки запроса.");
        requests.status(user.getName(),requestId,"CANCELLED");
    }
    private HarnessRequestRepository.RequestView owned(String owner,String agent,UUID chat,UUID id) {
        var value=requests.find(owner,id).orElseThrow(()->new ChatFailure(ChatFailure.Kind.NOT_FOUND,"Запрос не найден"));
        if(!value.context().chatId().equals(chat) || !value.context().agentId().equals(agent)) throw new ChatFailure(ChatFailure.Kind.NOT_FOUND,"Запрос не найден");
        return value;
    }
    private ResponseEntity<Flux<ServerSentEvent<Object>>> response(Flux<HarnessService.Event> events) {
        return ResponseEntity.ok().header("X-Accel-Buffering","no").body(events.map(e->ServerSentEvent.builder(e.data()).event(e.type()).build()));
    }
}
