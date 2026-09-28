package com.github.vladsaraykin.aichat.rag.application;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

public interface DocumentStore {
    long MAX_BYTES = 20L * 1024 * 1024;
    record Stored(UUID key, Path path, long size, String sha256) { }
    Stored save(InputStream input);
    void discard(Stored file);
}
