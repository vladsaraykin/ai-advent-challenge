package com.github.vladsaraykin.aichat.history;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.util.Assert;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class JsonFileChatHistoryStore implements ChatHistoryStore {

    private final ObjectMapper objectMapper;
    private final Path historyFile;
    private final ReentrantLock lock = new ReentrantLock();

    public JsonFileChatHistoryStore(ObjectMapper objectMapper, Path historyFile) {
        this.objectMapper = objectMapper;
        this.historyFile = historyFile.toAbsolutePath().normalize();
        Assert.state(this.historyFile.getParent() != null, "History file must have a parent directory");
    }

    @Override
    public ChatConversation load() {
        lock.lock();
        try {
            if (Files.notExists(historyFile)) {
                return ChatConversation.empty();
            }
            return objectMapper.readValue(historyFile.toFile(), ChatConversation.class);
        } catch (JacksonException exception) {
            throw new ChatHistoryException("Could not read chat history from " + historyFile, exception);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void save(ChatConversation conversation) {
        lock.lock();
        try {
            Files.createDirectories(historyFile.getParent());
            Path temporaryFile = historyFile.resolveSibling(historyFile.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporaryFile.toFile(), conversation);
            moveAtomicallyWhenSupported(temporaryFile, historyFile);
        } catch (IOException | JacksonException exception) {
            throw new ChatHistoryException("Could not write chat history to " + historyFile, exception);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            Files.deleteIfExists(historyFile);
        } catch (IOException exception) {
            throw new ChatHistoryException("Could not clear chat history at " + historyFile, exception);
        } finally {
            lock.unlock();
        }
    }

    private static void moveAtomicallyWhenSupported(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
