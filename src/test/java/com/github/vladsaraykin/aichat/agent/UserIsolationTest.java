package com.github.vladsaraykin.aichat.agent;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import com.github.vladsaraykin.aichat.agent.infrastructure.FileChatRepository;
import com.github.vladsaraykin.aichat.user.application.UserRepository;
import com.github.vladsaraykin.aichat.user.domain.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class UserIsolationTest {
    @TempDir Path directory;
    @Test void isolatesChatsAndInjectsTheAuthenticatedProfileIntoEveryAnswer() throws Exception {
        var prompts = new ArrayList<String>();
        ConversationModel model = (definition, messages) -> {
            prompts.add(definition.systemPrompt());
            return new ConversationModel.Reply("ok", new ChatMessage.Metrics(definition.model(), 1,
                    1, 0, 1, 2, 0, 2, 4, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "stop"));
        };
        UserRepository users = new UserRepository() {
            @Override public Optional<UserAccount> account(String username) { return Optional.empty(); }
            @Override public UserProfile profile(String username) {
                return new UserProfile(username, username, username.equals("alice") ? "ALICE_STYLE" : "BOB_STYLE",
                        "Markdown", List.of("Не использовать emoji"), 0, Instant.now());
            }
            @Override public UserProfile create(UserAccount a, UserProfile p) { throw new UnsupportedOperationException(); }
            @Override public UserProfile saveProfile(String u, long v, UserProfile p) { throw new UnsupportedOperationException(); }
        };
        var service = new ChatService(LegacyAgents.catalog(model), new FileChatRepository(directory.toString()), null, users);
        var aliceChat = service.create("alice", "chef");
        service.send("alice", "chef", aliceChat.id(), UUID.randomUUID(), "Рецепт");
        var bobChat = service.create("bob", "chef");
        service.send("bob", "chef", bobChat.id(), UUID.randomUUID(), "Рецепт");

        assertThat(prompts.get(0)).contains("ALICE_STYLE").doesNotContain("BOB_STYLE");
        assertThat(prompts.get(1)).contains("BOB_STYLE").doesNotContain("ALICE_STYLE");
        assertThat(service.list("alice", "chef")).extracting(Chat::id).containsExactly(aliceChat.id());
        assertThat(service.list("bob", "chef")).extracting(Chat::id).containsExactly(bobChat.id());
        assertThatThrownBy(() -> service.get("bob", "chef", aliceChat.id())).isInstanceOf(ChatFailure.class);
    }
}
