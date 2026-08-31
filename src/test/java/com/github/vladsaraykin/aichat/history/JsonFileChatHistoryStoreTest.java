package com.github.vladsaraykin.aichat.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class JsonFileChatHistoryStoreTest {

    @TempDir
    Path directory;

    @Test
    void savesLoadsAndClearsConversation() {
        var objectMapper = JsonMapper.builder().findAndAddModules().build();
        var store = new JsonFileChatHistoryStore(objectMapper, directory.resolve("chat.json"));
        var conversation = new ChatConversation(
                "Be concise",
                List.of(new ChatMessage(ChatMessage.Role.USER, "Hello", Instant.parse("2026-01-01T00:00:00Z"))),
                Instant.parse("2026-01-01T00:00:01Z"));

        store.save(conversation);

        assertThat(store.load()).isEqualTo(conversation);
        store.clear();
        assertThat(store.load().messages()).isEmpty();
    }
}
