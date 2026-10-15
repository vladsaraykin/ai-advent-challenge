package com.github.vladsaraykin.aichat.rag.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Manifest for documents exclusively owned by the project-doc synchronizer. */
public class ProjectDocumentSyncRepository {
    public record Entry(String path, String activeSha, UUID activeDocument, String pendingSha,
                 UUID pendingDocument, UUID pendingRun) { }
    private final JdbcTemplate jdbc;
    public ProjectDocumentSyncRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Entry> list(String owner) {
        return jdbc.query("SELECT * FROM rag.project_document_sync WHERE owner_username=?", this::map, owner);
    }
    public List<UUID> activeDocumentIds(String owner) {
        return jdbc.query("SELECT active_document_id FROM rag.project_document_sync "
                + "WHERE owner_username=? AND active_document_id IS NOT NULL ORDER BY source_path",
                (rs, row) -> rs.getObject(1, UUID.class), owner);
    }
    public void pending(String owner, String path, String sha, UUID document, UUID run) {
        jdbc.update("""
                INSERT INTO rag.project_document_sync(owner_username,source_path,pending_sha256,pending_document_id,pending_run_id)
                VALUES(?,?,?,?,?) ON CONFLICT(owner_username,source_path) DO UPDATE SET
                  pending_sha256=excluded.pending_sha256,pending_document_id=excluded.pending_document_id,
                  pending_run_id=excluded.pending_run_id,updated_at=now()
                WHERE rag.project_document_sync.pending_document_id IS NULL
                """, owner, path, sha, document, run);
    }
    public void activate(String owner, String path, String sha) {
        jdbc.update("""
                UPDATE rag.project_document_sync SET active_sha256=pending_sha256, active_document_id=pending_document_id,
                  pending_sha256=NULL,pending_document_id=NULL,pending_run_id=NULL,updated_at=now()
                WHERE owner_username=? AND source_path=? AND pending_sha256=?
                """, owner, path, sha);
    }
    public void clearPending(String owner, String path) {
        jdbc.update("UPDATE rag.project_document_sync SET pending_sha256=NULL,pending_document_id=NULL,"
                + "pending_run_id=NULL,updated_at=now() WHERE owner_username=? AND source_path=?", owner, path);
    }
    public List<UUID> remove(String owner, String path) {
        return jdbc.query("DELETE FROM rag.project_document_sync WHERE owner_username=? AND source_path=? "
                + "RETURNING active_document_id,pending_document_id", rs -> {
            if (!rs.next()) return List.of();
            var ids = new java.util.ArrayList<UUID>();
            var active = rs.getObject(1, UUID.class); var pending = rs.getObject(2, UUID.class);
            if (active != null) ids.add(active); if (pending != null) ids.add(pending);
            return List.copyOf(ids);
        }, owner, path);
    }
    private Entry map(ResultSet rs, int row) throws SQLException {
        return new Entry(rs.getString("source_path"), rs.getString("active_sha256"),
                rs.getObject("active_document_id", UUID.class), rs.getString("pending_sha256"),
                rs.getObject("pending_document_id", UUID.class), rs.getObject("pending_run_id", UUID.class));
    }
}
