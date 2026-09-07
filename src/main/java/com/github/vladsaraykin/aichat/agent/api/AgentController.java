package com.github.vladsaraykin.aichat.agent.api;

import com.github.vladsaraykin.aichat.agent.application.ChatService;
import com.github.vladsaraykin.aichat.agent.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents")
public class AgentController {
    private final ChatService service;
    public AgentController(ChatService service) { this.service = service; }
    public record AgentView(String id, String name, String description, String model, int maxCompletionTokens) {
        static AgentView from(AgentDefinition definition) {
            return new AgentView(definition.id(), definition.name(), definition.description(),
                    definition.model(), definition.maxCompletionTokens());
        }
    }
    public record ChatSummary(UUID id, String agentId, String title, Instant updatedAt, int messageCount) {
        static ChatSummary from(Chat chat) {
            return new ChatSummary(chat.id(), chat.agentId(), chat.title(), chat.updatedAt(), chat.messages().size());
        }
    }
    public record SendRequest(@NotNull UUID messageId,
                              @NotBlank @Size(max = 12000) String content) { }

    @GetMapping public List<AgentView> agents() { return service.agents().stream().map(AgentView::from).toList(); }
    @GetMapping("/{agentId}/chats") public List<ChatSummary> chats(@PathVariable String agentId) {
        return service.list(agentId).stream().map(ChatSummary::from).toList();
    }
    @PostMapping("/{agentId}/chats") @ResponseStatus(HttpStatus.CREATED)
    public Chat create(@PathVariable String agentId) { return service.create(agentId); }
    @GetMapping("/{agentId}/chats/{chatId}")
    public Chat chat(@PathVariable String agentId, @PathVariable UUID chatId) { return service.get(agentId, chatId); }
    @PostMapping("/{agentId}/chats/{chatId}/messages")
    public Chat send(@PathVariable String agentId, @PathVariable UUID chatId, @Valid @RequestBody SendRequest request) {
        return service.send(agentId, chatId, request.messageId(), request.content().strip());
    }
}
