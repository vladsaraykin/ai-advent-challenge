package com.github.vladsaraykin.aichat.rag.infrastructure;

import com.github.vladsaraykin.aichat.rag.application.DocumentRepository;
import com.github.vladsaraykin.aichat.rag.application.RagFailure;
import com.github.vladsaraykin.aichat.rag.domain.ExtractedText;
import com.github.vladsaraykin.aichat.rag.domain.RagDocument;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

public class JdbcDocumentRepository implements DocumentRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public JdbcDocumentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void insert(RagDocument document) {
        try {
            jdbc.update("""
                    INSERT INTO rag.documents (id, owner_username, original_filename, media_type, storage_key,
                        byte_size, sha256, extracted_text, extraction_metadata, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """, document.id(), document.owner(), document.filename(), document.mediaType(), document.storageKey(),
                    document.byteSize(), document.sha256(), document.extracted().text(),
                    mapper.writeValueAsString(document.extracted().metadata()), Timestamp.from(document.createdAt()));
        } catch (RuntimeException exception) { throw RagFailure.storage(); }
    }

    @Override public List<RagDocument.Summary> list(String owner, int limit, int offset) {
        try {
            return jdbc.query("""
                    SELECT id, original_filename, media_type, byte_size, length(extracted_text) AS characters, created_at
                    FROM rag.documents WHERE owner_username=? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                    """, (rs, row) -> new RagDocument.Summary(rs.getObject("id", UUID.class), rs.getString("original_filename"),
                    rs.getString("media_type"), rs.getLong("byte_size"), rs.getInt("characters"),
                    rs.getTimestamp("created_at").toInstant()), owner, limit, offset);
        } catch (RuntimeException exception) { throw RagFailure.storage(); }
    }

    @Override public Optional<RagDocument> find(String owner, UUID id) {
        try {
            return jdbc.query("SELECT * FROM rag.documents WHERE owner_username=? AND id=?", (rs, row) ->
                    new RagDocument(rs.getObject("id", UUID.class), rs.getString("owner_username"),
                            rs.getString("original_filename"), rs.getString("media_type"), rs.getObject("storage_key", UUID.class),
                            rs.getLong("byte_size"), rs.getString("sha256"),
                            new ExtractedText(rs.getString("extracted_text"), mapper.readValue(rs.getString("extraction_metadata"), ExtractedText.Metadata.class)),
                            rs.getTimestamp("created_at").toInstant()), owner, id).stream().findFirst();
        } catch (RuntimeException exception) { throw RagFailure.storage(); }
    }
}
