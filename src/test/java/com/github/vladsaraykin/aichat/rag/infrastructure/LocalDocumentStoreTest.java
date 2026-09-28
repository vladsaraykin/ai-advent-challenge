package com.github.vladsaraykin.aichat.rag.infrastructure;

import com.github.vladsaraykin.aichat.rag.application.*;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class LocalDocumentStoreTest {
    @TempDir Path directory;
    @Test void savesRandomKeysHashesAndDiscardsOnlyOwnedPath() throws Exception {
        var store = new LocalDocumentStore(directory.toRealPath());
        var file = store.save(new ByteArrayInputStream("hello".getBytes()));
        assertThat(file.sha256()).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(file.size()).isEqualTo(5);
        assertThat(Files.readString(file.path())).isEqualTo("hello");
        assertThat(file.path().getFileName().toString()).isEqualTo(file.key() + ".bin");
        assertThatThrownBy(() -> store.discard(new DocumentStore.Stored(file.key(), directory.resolve("other"), 0, "")))
                .isInstanceOf(RagFailure.class);
        store.discard(file); assertThat(file.path()).doesNotExist();
    }

    @Test void rejectsEmptyOversizedAndInterruptedUploadsWithoutLeavingFiles() throws Exception {
        var store = new LocalDocumentStore(directory.toRealPath());
        assertThatThrownBy(() -> store.save(InputStream.nullInputStream())).hasMessageContaining("пуст");
        assertThatThrownBy(() -> store.save(new InputStream() {
            @Override public int read() { return 0; }
            @Override public int read(byte[] bytes) { return bytes.length; }
        })).hasMessageContaining("20 МиБ");
        assertThatThrownBy(() -> store.save(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("sensitive upstream details"); }
        })).hasMessageNotContaining("sensitive");
        try (var files = Files.list(directory)) { assertThat(files).isEmpty(); }
    }

    @Test void refusesSymlinkRoot() throws Exception {
        var link = directory.resolve("link"); Files.createSymbolicLink(link, directory);
        assertThatThrownBy(() -> new LocalDocumentStore(link)).isInstanceOf(IOException.class);
    }
}
