package com.github.vladsaraykin.aichat.agent;

import com.github.vladsaraykin.aichat.agent.api.*;
import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.infrastructure.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

class AgentControllerTest {
    @TempDir Path directory;
    @Test void forwardsMultipleMcpServersAndRejectsInvalidSelections() throws Exception {
        var selected = new java.util.concurrent.atomic.AtomicReference<java.util.List<String>>();
        ConversationModel model = new ConversationModel() {
            @Override public Reply reply(AgentDefinition definition, java.util.List<ChatMessage> messages) {
                return new Reply("ok", new ChatMessage.Metrics(definition.model(), 1, 1, 0, 0,
                        1, 0, 1, 2, null, null, null, "stop"));
            }
            @Override public reactor.core.publisher.Flux<StreamPart> stream(AgentDefinition definition,
                    ContextSummary summary, java.util.List<ChatMessage> messages, java.util.List<String> servers) {
                selected.set(servers);
                return reactor.core.publisher.Flux.just(StreamPart.completed(reply(definition, messages)));
            }
        };
        var service = new ChatService(LegacyAgents.catalog(model), new FileChatRepository(directory.toString()));
        var mvc = MockMvcBuilders.standaloneSetup(new AgentController(service))
                .setControllerAdvice(new ChatExceptionHandler()).build();
        var chat = service.create("chef");
        String path = "/api/agents/chef/chats/" + chat.id() + "/messages";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("""
                {"messageId":"%s","content":"Отчёт","mcpServerIds":["filesystem","expenses","expenses"]}
                """.formatted(UUID.randomUUID()))).andExpect(status().isOk());
        assertThat(selected.get()).containsExactly("expenses", "filesystem");
        for (String ids : java.util.List.of("[null]", "[\" \" ]", "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\"]")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("""
                    {"messageId":"%s","content":"Отчёт","mcpServerIds":%s}
                    """.formatted(UUID.randomUUID(), ids))).andExpect(status().isBadRequest());
        }
    }
    @Test void servesAgentsAndValidatesRequestsAndChatOwnership() throws Exception {
        var registry = LegacyAgents.catalog((definition, messages) -> new ConversationModel.Reply("ok",
                new ChatMessage.Metrics(definition.model(), 12, 2, 0, 3, 5, 0, 5, 10,
                        new BigDecimal("0.00000200"), new BigDecimal("0.00000800"),
                        new BigDecimal("0.00001000"), "stop")));
        var service = new ChatService(registry, new FileChatRepository(directory.toString()));
        var controller = new AgentController(service);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ChatExceptionHandler()).build();
        mvc.perform(get("/api/agents")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].id").value("architect"))
                .andExpect(jsonPath("$[1].id").value("assistant"))
                .andExpect(jsonPath("$[3].id").value("techno"))
                .andExpect(jsonPath("$[0].contextCompression").value(true))
                .andExpect(jsonPath("$[0].recentMessages").value(10))
                .andExpect(jsonPath("$[0].summaryBatchSize").value(10))
                .andExpect(jsonPath("$[0].systemPrompt").doesNotExist())
                .andExpect(jsonPath("$[0].summaryPrompt").doesNotExist())
                .andExpect(jsonPath("$[0].pricing").doesNotExist());
        mvc.perform(post("/api/agents/chef/chats")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.messages").isEmpty());
        mvc.perform(post("/api/agents/chef/chats").contentType(MediaType.APPLICATION_JSON)
                .content("{\"strategy\":\"SLIDING_WINDOW\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.strategy").value("SLIDING_WINDOW"));
        mvc.perform(post("/api/agents/chef/chats").contentType(MediaType.APPLICATION_JSON)
                .content("{\"strategy\":\"UNKNOWN\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/agents/chef/chats").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        Chat chat = service.create("architect");
        String path = "/api/agents/architect/chats/" + chat.id() + "/messages";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"messageId\":\"" + UUID.randomUUID() + "\",\"content\":\"Привет\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[1].content").value("ok"))
                .andExpect(jsonPath("$.messages[1].metrics.totalCostUsd").value(0.00001000));
        var stream = controller.stream("architect", chat.id(),
                new AgentController.SendRequest(UUID.randomUUID(), "Поток"));
        assertThat(stream.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
        var events = stream.getBody().collectList().block();
        assertThat(events).extracting(event -> event.event()).containsExactly("started", "completed");
        assertThat(((ChatService.StreamEvent) events.getLast().data()).chat().messages().getLast().content())
                .isEqualTo("ok");
        mvc.perform(get("/api/agents/chef/chats/" + chat.id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/agents/architect/chats/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/agents/chef/chats/" + chat.id())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/agents/architect/chats/" + chat.id())).andExpect(status().isNoContent());
        mvc.perform(get("/api/agents/architect/chats/" + chat.id())).andExpect(status().isNotFound());
    }
}
