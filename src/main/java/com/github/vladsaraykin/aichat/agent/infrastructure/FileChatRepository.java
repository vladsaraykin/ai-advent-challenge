package com.github.vladsaraykin.aichat.agent.infrastructure;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.Chat;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class FileChatRepository implements ChatRepository {
    private final Path root;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public FileChatRepository(@Value("${app.chats.directory:data/agent-chats}") String directory) throws IOException {
        root = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }
    private Path directory(String agentId) {
        if (agentId == null || !agentId.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new ChatFailure(ChatFailure.Kind.NOT_FOUND, "Агент не найден");
        }
        return root.resolve(agentId);
    }
    @Override public List<Chat> list(String agentId) {
        Path directory = directory(agentId);
        if (!Files.exists(directory)) return List.of();
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::read).flatMap(this::flatten).sorted(Comparator.comparing(Chat::updatedAt).reversed()).toList();
        } catch (IOException exception) { throw storageFailure(); }
    }
    @Override public Chat get(String agentId, UUID chatId) {
        Chat chat = list(agentId).stream().filter(c -> c.id().equals(chatId)).findFirst()
                .orElseThrow(() -> new ChatFailure(ChatFailure.Kind.NOT_FOUND, "Чат не найден у этого агента"));
        if (!chat.agentId().equals(agentId) || !chat.id().equals(chatId)) throw storageFailure();
        return chat;
    }
    private Chat read(Path path) {
        try { return mapper.readValue(Files.readString(path), Chat.class); }
        catch (Exception exception) { throw storageFailure(); }
    }
    private java.util.stream.Stream<Chat> flatten(Chat chat) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(chat), chat.branches().stream().flatMap(this::flatten));
    }
    private Chat replace(Chat root, Chat updated) {
        if (root.id().equals(updated.id())) return updated;
        return root.withBranches(root.branches().stream().map(child -> replace(child, updated)).toList());
    }
    @Override public synchronized void delete(String agentId, UUID chatId) {
        Chat chat = get(agentId, chatId);
        if (chat.parentChatId() != null) {
            Chat parent = get(agentId, chat.parentChatId());
            save(parent.withBranches(parent.branches().stream().filter(c -> !c.id().equals(chatId)).toList()));
        } else {
            try { Files.delete(directory(agentId).resolve(chatId + ".json")); }
            catch (IOException exception) { throw storageFailure(); }
        }
    }
    @Override public synchronized void save(Chat chat) {
        if (chat.parentChatId() != null) {
            Chat root = get(chat.agentId(), chat.parentChatId());
            while (root.parentChatId() != null) root = get(chat.agentId(), root.parentChatId());
            chat = replace(root, chat);
        }
        Path temp = null;
        try {
            Path directory = directory(chat.agentId());
            Files.createDirectories(directory);
            temp = Files.createTempFile(directory, ".chat-", ".tmp");
            Files.writeString(temp, mapper.writeValueAsString(chat), StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temp, directory.resolve(chat.id() + ".json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception exception) { throw storageFailure(); }
        finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            }
        }
    }
    private static ChatFailure storageFailure() {
        return new ChatFailure(ChatFailure.Kind.STORAGE, "Не удалось прочитать или сохранить историю чата");
    }
}
