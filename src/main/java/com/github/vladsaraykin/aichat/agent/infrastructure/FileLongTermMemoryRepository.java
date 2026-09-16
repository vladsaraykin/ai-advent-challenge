package com.github.vladsaraykin.aichat.agent.infrastructure;

import com.github.vladsaraykin.aichat.agent.application.*;
import com.github.vladsaraykin.aichat.agent.domain.LongTermMemory;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class FileLongTermMemoryRepository implements LongTermMemoryRepository {
    private final Path directory;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public FileLongTermMemoryRepository(@Value("${app.memory.directory:${app.chats.directory:data/agent-chats}/_long-term}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }
    private Path path(String ownerId, String agentId) {
        if (ownerId == null || !ownerId.matches("[a-z0-9][a-z0-9._-]{2,39}")) throw new ChatFailure(ChatFailure.Kind.NOT_FOUND, "Пользователь не найден");
        if (agentId == null || !agentId.matches("[a-z][a-z0-9-]{0,63}")) throw new ChatFailure(ChatFailure.Kind.NOT_FOUND, "Агент не найден");
        return LongTermMemoryRepository.LEGACY_OWNER.equals(ownerId) ? directory.resolve(agentId + ".json")
                : directory.resolve(ownerId).resolve(agentId + ".json");
    }
    @Override public synchronized LongTermMemory get(String ownerId, String agentId) {
        var path = path(ownerId, agentId);
        if (!Files.exists(path)) return LongTermMemory.empty();
        try { return mapper.readValue(Files.readString(path), LongTermMemory.class); }
        catch (Exception e) { throw failure(); }
    }
    @Override public synchronized LongTermMemory put(String ownerId, String agentId, long expectedVersion, LongTermMemory.Entry entry) {
        var current = get(ownerId, agentId);
        MemoryService.checkVersion(current.version(), expectedVersion);
        var entries = new ArrayList<>(current.entries());
        entries.removeIf(e -> e.id().equals(entry.id()));
        if (entries.stream().anyMatch(e -> e.scope() == entry.scope() && e.projectKey().equals(entry.projectKey()) && e.key().equals(entry.key())))
            throw new ChatFailure(ChatFailure.Kind.INVALID, "Такой ключ уже сохранён в этой области. Измените существующую запись.");
        if (entries.size() >= 100) throw new ChatFailure(ChatFailure.Kind.INVALID, "Достигнут лимит: 100 записей памяти.");
        entries.add(entry);
        var resolved = new HashSet<>(current.resolvedProposals());
        if (entry.sourceMessageId() != null) resolved.add(entry.id());
        return save(ownerId, agentId, new LongTermMemory(current.version() + 1, entries, resolved));
    }
    @Override public synchronized LongTermMemory delete(String ownerId, String agentId, long expectedVersion, UUID entryId) {
        var current = get(ownerId, agentId);
        MemoryService.checkVersion(current.version(), expectedVersion);
        if (current.entries().stream().noneMatch(e -> e.id().equals(entryId))) throw new ChatFailure(ChatFailure.Kind.NOT_FOUND, "Запись не найдена");
        return save(ownerId, agentId, new LongTermMemory(current.version() + 1, current.entries().stream().filter(e -> !e.id().equals(entryId)).toList(), current.resolvedProposals()));
    }
    private LongTermMemory save(String ownerId, String agentId, LongTermMemory memory) {
        Path temp = null;
        try {
            Path target = path(ownerId, agentId);
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".memory-", ".tmp");
            Files.writeString(temp, mapper.writeValueAsString(memory));
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return memory;
        } catch (Exception e) { throw failure(); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (java.io.IOException ignored) { } }
    }
    private static ChatFailure failure() { return new ChatFailure(ChatFailure.Kind.STORAGE, "Не удалось прочитать или сохранить долговременную память"); }
}
