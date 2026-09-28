package com.github.vladsaraykin.aichat.rag.infrastructure;

import com.github.vladsaraykin.aichat.rag.application.*;
import com.github.vladsaraykin.aichat.rag.domain.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

public final class JdbcIndexRepository implements IndexRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();
    public JdbcIndexRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc; this.transactions = transactions;
    }

    @Override public void recoverInterrupted() {
        jdbc.update("""
                UPDATE rag.index_runs SET status='FAILED', error_code='INTERRUPTED', finished_at=now(),
                  duration_ms=GREATEST(0, (extract(epoch from (now()-created_at))*1000)::bigint)
                WHERE status NOT IN ('COMPLETED','FAILED')
                """);
    }

    @Override public UUID create(RagDocument document, DocumentChunk.Strategy strategy,
                                  ChunkingSettings settings, String model) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rag.index_runs(id,owner_username,document_id,strategy,embedding_model,
                  embedding_dimensions,chunking_parameters,input_sha256,prompt_tokens)
                VALUES (?,?,?,?,?,768,?::jsonb,?,0)
                """, id, document.owner(), document.id(), strategy.name(), model,
                json.writeValueAsString(settings), document.sha256());
        return id;
    }

    @Override public void prepare(String owner, UUID run, List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) throw new EmbeddingModel.Failure("EMPTY_INDEX");
        transactions.executeWithoutResult(tx -> {
            requireOne(jdbc.update("UPDATE rag.index_runs SET status='EMBEDDING',total_chunks=? "
                    + "WHERE owner_username=? AND id=? AND status='PENDING'", chunks.size(), owner, run));
            for (var chunk : chunks) {
                var metadata = new LinkedHashMap<String, Object>();
                metadata.put("previewChunkId", chunk.chunkId());
                metadata.put("pageStart", chunk.pageStart()); metadata.put("pageEnd", chunk.pageEnd());
                metadata.put("tokenEstimator", "cl100k_base");
                UUID id = UUID.nameUUIDFromBytes((run + ":" + chunk.chunkId()).getBytes(StandardCharsets.UTF_8));
                jdbc.update("""
                        INSERT INTO rag.chunks(id,owner_username,run_id,ordinal,source,title,section,content,
                          start_offset,end_offset,token_count,metadata)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                        """, id, owner, run, chunk.ordinal(), chunk.source(), chunk.title(), chunk.section(),
                        chunk.content(), chunk.startOffset(), chunk.endOffset(), chunk.estimatedTokens(), json.writeValueAsString(metadata));
            }
        });
    }

    @Override public void saveEmbedding(String owner, UUID run, int ordinal, EmbeddingModel.Result result) {
        if (result.vector().length != 768) throw new EmbeddingModel.Failure("INVALID_EMBEDDING");
        var vector = new StringJoiner(",", "[", "]");
        for (float value : result.vector()) {
            if (!Float.isFinite(value)) throw new EmbeddingModel.Failure("INVALID_EMBEDDING");
            vector.add(Float.toString(value));
        }
        transactions.executeWithoutResult(tx -> {
            requireOne(jdbc.update("UPDATE rag.index_runs SET embedded_chunks=embedded_chunks+1, "
                    + "prompt_tokens=prompt_tokens+? WHERE owner_username=? AND id=? AND status='EMBEDDING'",
                    result.promptTokens(), owner, run));
            requireOne(jdbc.update("UPDATE rag.chunks SET embedding=?::public.vector "
                    + "WHERE owner_username=? AND run_id=? AND ordinal=? AND embedding IS NULL", vector.toString(), owner, run, ordinal));
        });
    }

    @Override public void finish(String owner, UUID run, long durationMs, String errorCode) {
        requireOne(jdbc.update("UPDATE rag.index_runs SET status=?,duration_ms=?,error_code=?,finished_at=now() "
                + "WHERE owner_username=? AND id=? AND status NOT IN ('COMPLETED','FAILED')",
                errorCode == null ? "COMPLETED" : "FAILED", durationMs, errorCode, owner, run));
    }

    @Override public IndexRun get(String owner, UUID run) {
        return jdbc.query("SELECT * FROM rag.index_runs WHERE owner_username=? AND id=?", this::mapRun, owner, run)
                .stream().findFirst().orElseThrow(() -> new RagFailure(RagFailure.Kind.NOT_FOUND, "Индекс не найден."));
    }

    @Override public List<IndexRun> list(String owner, UUID document) {
        return jdbc.query("SELECT * FROM rag.index_runs WHERE owner_username=? AND document_id=? "
                + "ORDER BY created_at DESC LIMIT 100", this::mapRun, owner, document);
    }

    @Override public List<DocumentChunk> chunks(String owner, UUID run, int offset, int limit) {
        return jdbc.query("SELECT *, (metadata->>'pageStart')::integer AS page_start, "
                + "(metadata->>'pageEnd')::integer AS page_end FROM rag.chunks "
                + "WHERE owner_username=? AND run_id=? ORDER BY ordinal LIMIT ? OFFSET ?", (rs, n) ->
                new DocumentChunk(rs.getObject("id", UUID.class), rs.getInt("ordinal"), rs.getString("source"),
                        rs.getString("title"), rs.getString("section"), rs.getObject("page_start", Integer.class),
                        rs.getObject("page_end", Integer.class), rs.getInt("start_offset"), rs.getInt("end_offset"),
                        rs.getString("content"), rs.getInt("token_count")), owner, run, limit, offset);
    }

    private IndexRun mapRun(ResultSet rs, int n) throws SQLException {
        var finished = rs.getTimestamp("finished_at");
        return new IndexRun(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                DocumentChunk.Strategy.valueOf(rs.getString("strategy")), rs.getString("status"),
                rs.getString("embedding_model"), rs.getInt("embedding_dimensions"), rs.getInt("total_chunks"),
                rs.getInt("embedded_chunks"), rs.getObject("prompt_tokens", Long.class), rs.getObject("duration_ms", Long.class),
                rs.getString("error_code"), rs.getTimestamp("created_at").toInstant(), finished == null ? null : finished.toInstant());
    }
    private void requireOne(int count) { if (count != 1) throw RagFailure.storage(); }
}
