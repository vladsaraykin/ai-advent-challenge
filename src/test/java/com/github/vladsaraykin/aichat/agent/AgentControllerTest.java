package com.github.vladsaraykin.aichat.agent;

import com.github.vladsaraykin.aichat.agent.api.*;
import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.infrastructure.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentControllerTest {
    @TempDir Path directory;
    @Test void servesAgentsAndValidatesRequestsAndChatOwnership() throws Exception {
        var registry = new AgentRegistry((definition, messages) -> new ConversationModel.Reply("ok",
                new ChatMessage.Metrics(definition.model(), 12, 5, 5, 10, "stop")), "classpath:agents/*.yaml");
        var service = new ChatService(registry, new FileChatRepository(directory.toString()));
        var mvc = MockMvcBuilders.standaloneSetup(new AgentController(service))
                .setControllerAdvice(new ChatExceptionHandler()).build();
        mvc.perform(get("/api/agents")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("architect"))
                .andExpect(jsonPath("$[0].systemPrompt").doesNotExist());
        mvc.perform(post("/api/agents/chef/chats")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.messages").isEmpty());
        Chat chat = service.create("architect");
        String path = "/api/agents/architect/chats/" + chat.id() + "/messages";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"messageId\":\"" + UUID.randomUUID() + "\",\"content\":\"Привет\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[1].content").value("ok"));
        mvc.perform(get("/api/agents/chef/chats/" + chat.id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/agents/architect/chats/not-a-uuid")).andExpect(status().isBadRequest());
    }
}
