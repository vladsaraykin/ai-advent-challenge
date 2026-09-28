package com.github.vladsaraykin.aichat.rag.infrastructure;

import com.github.vladsaraykin.aichat.rag.application.DocumentStore;
import com.github.vladsaraykin.aichat.rag.application.RagFailure;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

public class LocalDocumentStore implements DocumentStore {
    private final Path root;

    public LocalDocumentStore(Path directory) throws IOException {
        root = directory.toAbsolutePath().normalize();
        for (Path path = root; path != null; path = path.getParent()) {
            if (Files.isSymbolicLink(path)) throw new IOException("RAG document directory must not contain symlinks");
        }
        Files.createDirectories(root);
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Override public Stored save(InputStream input) {
        UUID key = UUID.randomUUID();
        Path path = root.resolve(key + ".bin");
        boolean created = false;
        try {
            if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else Files.createFile(path);
            created = true;
            var digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (var output = Files.newOutputStream(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    if (size > MAX_BYTES) throw new RagFailure(RagFailure.Kind.TOO_LARGE, "Размер файла превышает 20 МиБ.");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            if (size == 0) throw new RagFailure(RagFailure.Kind.INVALID, "Файл пуст.");
            return new Stored(key, path, size, HexFormat.of().formatHex(digest.digest()));
        } catch (Exception exception) {
            if (created) {
                try { Files.deleteIfExists(path); } catch (IOException cleanup) { throw RagFailure.storage(); }
            }
            if (exception instanceof RagFailure failure) throw failure;
            throw RagFailure.storage();
        }
    }

    @Override public void discard(Stored file) {
        Path expected = root.resolve(file.key() + ".bin");
        if (!expected.equals(file.path())) throw RagFailure.storage();
        try { Files.deleteIfExists(expected); } catch (IOException exception) { throw RagFailure.storage(); }
    }
}
