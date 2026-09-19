package com.github.vladsaraykin.aichat.agent.api;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents/{agentId}")
public class MemoryController {
    private final ChatService service;
    public MemoryController(ChatService service) { this.service = service; }
    private static String owner(Principal principal) { return principal == null ? ChatRepository.LEGACY_OWNER : principal.getName(); }
    public record Version(@PositiveOrZero long version) { }
    public record TaskEdit(@PositiveOrZero long version, @NotNull @Size(max=80) String projectKey,
                           @NotNull @Valid TaskFields task) { }
    public record TaskFields(@NotNull @Size(max=1000) String goal,
                             @NotNull @Size(max=40) Map<String, String> requirements,
                             @NotNull @Size(max=40) Map<String, String> constraints,
                             @NotNull @Size(max=40) Map<String, String> decisions,
                             @NotNull @Size(max=20) List<String> openQuestions) {
        MemoryService.TaskData data() { return new MemoryService.TaskData(goal, requirements, constraints, decisions, openQuestions); }
    }
    public record Confirmation(@PositiveOrZero long version, @PositiveOrZero long taskVersion) { }
    public record EntryEdit(@PositiveOrZero long version, @NotNull WorkingMemory.Scope scope,
                            @NotNull @Size(max=80) String projectKey, @NotBlank @Size(max=80) String key,
                            @NotBlank @Size(max=500) String value) { }
    public record InvariantEdit(@PositiveOrZero long version, @NotNull TaskInvariants.Type type,
                                @NotBlank @Size(max=100) String title,
                                @NotBlank @Size(max=1000) String rule,
                                @NotNull @Size(max=500) String rationale) { }
    @GetMapping("/memory") public LongTermMemory memory(Principal principal, @PathVariable String agentId) { return service.memory(owner(principal), agentId); }
    @PutMapping("/memory/{id}") public LongTermMemory put(Principal principal, @PathVariable String agentId, @PathVariable UUID id,
            @Valid @RequestBody EntryEdit request) {
        return service.putMemory(owner(principal), agentId, request.version(), id, request.scope(), request.projectKey(), request.key(), request.value());
    }
    @DeleteMapping("/memory/{id}") public LongTermMemory delete(Principal principal, @PathVariable String agentId, @PathVariable UUID id,
            @Valid @RequestBody Version request) { return service.deleteMemory(owner(principal), agentId, request.version(), id); }
    @PutMapping("/chats/{chatId}/task") public Chat task(Principal principal, @PathVariable String agentId, @PathVariable UUID chatId,
            @Valid @RequestBody TaskEdit request) {
        return service.editTask(owner(principal), agentId, chatId, request.version(), request.projectKey(), request.task().data());
    }
    @PostMapping("/chats/{chatId}/task/advance") public Chat advance(Principal principal, @PathVariable String agentId, @PathVariable UUID chatId,
            @Valid @RequestBody Version request) { return service.advanceTask(owner(principal), agentId, chatId, request.version()); }
    @PostMapping("/chats/{chatId}/task/pause") public Chat pause(Principal principal, @PathVariable String agentId, @PathVariable UUID chatId,
            @Valid @RequestBody Version request) { return service.pauseTask(owner(principal), agentId, chatId, request.version()); }
    @PostMapping("/chats/{chatId}/task/resume") public Chat resume(Principal principal, @PathVariable String agentId, @PathVariable UUID chatId,
            @Valid @RequestBody Version request) { return service.resumeTask(owner(principal), agentId, chatId, request.version()); }
    @PutMapping("/chats/{chatId}/invariants/{id}") public Chat putInvariant(Principal principal,
            @PathVariable String agentId, @PathVariable UUID chatId, @PathVariable UUID id,
            @Valid @RequestBody InvariantEdit request) {
        return service.putInvariant(owner(principal), agentId, chatId, request.version(),
                new TaskInvariants.Entry(id, request.type(), request.title(), request.rule(),
                        request.rationale(), java.time.Instant.now()));
    }
    @DeleteMapping("/chats/{chatId}/invariants/{id}") public Chat deleteInvariant(Principal principal,
            @PathVariable String agentId, @PathVariable UUID chatId, @PathVariable UUID id,
            @Valid @RequestBody Version request) {
        return service.deleteInvariant(owner(principal), agentId, chatId, request.version(), id);
    }
    @PostMapping("/chats/{chatId}/proposals/{id}/accept") public LongTermMemory accept(Principal principal, @PathVariable String agentId,
            @PathVariable UUID chatId, @PathVariable UUID id, @Valid @RequestBody Confirmation request) {
        return service.acceptProposal(owner(principal), agentId, chatId, request.version(), request.taskVersion(), id);
    }
    @PostMapping("/chats/{chatId}/proposals/{id}/reject") public Chat reject(Principal principal, @PathVariable String agentId,
            @PathVariable UUID chatId, @PathVariable UUID id, @Valid @RequestBody Version request) {
        return service.rejectProposal(owner(principal), agentId, chatId, request.version(), id);
    }
}
