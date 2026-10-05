package com.github.vladsaraykin.aichat.rag.infrastructure;

import com.github.vladsaraykin.aichat.rag.application.*;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Original files live in bytea. A bounded private scratch file exists only during parsing. */
public final class JdbcDocumentStore implements DocumentStore {
    private final JdbcTemplate jdbc;
    public JdbcDocumentStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public Stored save(InputStream input) {
        Path scratch=null;
        try {
            byte[] bytes=input.readNBytes((int)MAX_BYTES+1);
            if(bytes.length==0) throw new RagFailure(RagFailure.Kind.INVALID,"Файл пуст.");
            if(bytes.length>MAX_BYTES) throw new RagFailure(RagFailure.Kind.TOO_LARGE,"Размер файла превышает 20 МиБ.");
            scratch=Files.createTempFile("rag-parse-",".bin");
            Files.write(scratch,bytes);
            UUID key=UUID.randomUUID();
            jdbc.update("INSERT INTO rag.document_files(storage_key,content) VALUES(?,?)",key,bytes);
            return new Stored(key,scratch,bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch(Exception error) {
            remove(scratch);
            if(error instanceof RagFailure failure) throw failure;
            throw RagFailure.storage();
        }
    }
    @Override public void discard(Stored file) {
        try { jdbc.update("DELETE FROM rag.document_files WHERE storage_key=?",file.key()); }
        finally { release(file); }
    }
    @Override public void release(Stored file) { remove(file.path()); }
    private void remove(Path path) {
        if(path!=null) try { Files.deleteIfExists(path); } catch(java.io.IOException e) { throw RagFailure.storage(); }
    }
}
