package com.github.vladsaraykin.aichat.agent;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatSearchTest {
    @Test void searchesTitlesCaseInsensitivelyAndScopesByOwnerAndAgent() {
        var repository = mock(ChatRepository.class);
        var catalog = mock(AgentCatalog.class);
        when(repository.list("alice", "assistant")).thenReturn(List.of(chat("Recipe Pasta"), chat("Other"), chat("Pasta Sauce")));
        var service = new ChatService(catalog, repository);
        assertThat(service.searchTitles("alice", "assistant", "  PASTA  ", 1)).extracting(ChatListItem::title).containsExactly("Recipe Pasta");
        verify(repository).list("alice", "assistant");
        verify(repository, never()).list(eq("bob"), anyString());
    }
    @Test void validatesBoundsBeforeReadingStorage() {
        var repository = mock(ChatRepository.class);
        var service = new ChatService(mock(AgentCatalog.class), repository);
        assertThatThrownBy(() -> service.searchTitles("alice", "assistant", "x".repeat(101), 50)).isInstanceOf(ChatFailure.class);
        assertThatThrownBy(() -> service.searchTitles("alice", "assistant", "x", 51)).isInstanceOf(ChatFailure.class);
        verifyNoInteractions(repository);
    }
    static Chat chat(String title) {
        return new Chat(UUID.randomUUID(), "assistant", title, Instant.now(), Instant.now(), List.of());
    }
}
