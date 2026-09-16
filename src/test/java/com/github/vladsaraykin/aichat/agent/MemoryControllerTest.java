package com.github.vladsaraykin.aichat.agent;

import com.github.vladsaraykin.aichat.agent.api.*;
import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.infrastructure.*;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class MemoryControllerTest {
    @TempDir Path directory;
    @Test void validatesMemoryEndpointsVersionsAndStreamsMemoryPhaseWithoutExposingPrompts() throws Exception {
        var registry = new AgentRegistry((d, messages) -> MemoryLayersTest.reply(
                d.systemPrompt().equals(d.memoryLayers().questionsPrompt()) ? "{\"questions\":[]}"
                        : MemoryLayersTest.extraction(d) ? MemoryLayersTest.EXTRACT : "ok"), "classpath:agents/*.yaml");
        var service = new ChatService(registry, new FileChatRepository(directory.resolve("chats").toString()),
                new FileLongTermMemoryRepository(directory.resolve("memory").toString()));
        var controller = new AgentController(service);
        var mvc = MockMvcBuilders.standaloneSetup(controller, new MemoryController(service))
                .setControllerAdvice(new ChatExceptionHandler()).build();
        mvc.perform(get("/api/agents")).andExpect(jsonPath("$[0].memoryLayers").value(true))
                .andExpect(jsonPath("$[0].memoryRecentMessages").value(10))
                .andExpect(jsonPath("$[0].systemPrompt").doesNotExist());
        mvc.perform(get("/api/agents/architect/memory")).andExpect(status().isOk()).andExpect(jsonPath("$.entries").isEmpty());
        mvc.perform(get("/api/agents/chef/memory")).andExpect(status().isBadRequest());
        UUID entry = UUID.randomUUID();
        String path = "/api/agents/architect/memory/" + entry;
        mvc.perform(put(path).contentType("application/json").content("""
                {"version":0,"scope":"GLOBAL","projectKey":"","key":"style","value":"кратко"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(path).contentType("application/json").content("""
                {"version":0,"scope":"GLOBAL","projectKey":"","key":"style","value":"подробно"}
                """)).andExpect(status().isConflict());
        mvc.perform(put(path).contentType("application/json").content("""
                {"version":1,"scope":"PROJECT","projectKey":"../outside","key":"style","value":"подробно"}
                """)).andExpect(status().isBadRequest());
        mvc.perform(delete(path).contentType("application/json").content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries").isEmpty());
        var chat = service.create("architect");
        String taskPath = "/api/agents/architect/chats/" + chat.id() + "/task";
        mvc.perform(put(taskPath).contentType("application/json").content("""
                {"version":0,"projectKey":"project-a","task":{"goal":"Test","requirements":{},"constraints":{},"decisions":{},"openQuestions":[null]}}
                """)).andExpect(status().isBadRequest());
        mvc.perform(post(taskPath + "/advance").contentType("application/json").content("{\"version\":0}"))
                .andExpect(status().isBadRequest());
        var stream = controller.stream("architect", chat.id(), new AgentController.SendRequest(UUID.randomUUID(), "Мой проект"));
        assertThat(stream.getBody().collectList().block()).extracting(e -> e.event()).contains("started", "updating_memory", "completed");
        mvc.perform(get("/api/agents/architect/chats/" + chat.id())).andExpect(jsonPath("$.workingMemory.goal").value("Сервис уведомлений"));
        long taskVersion = service.get("architect", chat.id()).workingMemory().version();
        mvc.perform(post(taskPath + "/pause").contentType("application/json").content("{\"version\":" + taskVersion + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workingMemory.status").value("PAUSED"))
                .andExpect(jsonPath("$.workingMemory.expectedAction").value("RESUME_TASK"));
        mvc.perform(post(taskPath + "/resume").contentType("application/json").content("{\"version\":" + (taskVersion + 1) + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workingMemory.status").value("ACTIVE"))
                .andExpect(jsonPath("$.workingMemory.currentStep").isNotEmpty());
        mvc.perform(put("/api/agents/chef/chats/" + chat.id() + "/task").contentType("application/json")
                .content("{\"version\":0,\"projectKey\":\"\",\"task\":" + MemoryLayersTest.TASK + "}"))
                .andExpect(status().isBadRequest());
    }
}
