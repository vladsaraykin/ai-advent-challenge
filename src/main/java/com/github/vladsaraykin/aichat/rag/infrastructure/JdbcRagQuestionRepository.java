package com.github.vladsaraykin.aichat.rag.infrastructure;

import com.github.vladsaraykin.aichat.rag.application.*;
import com.github.vladsaraykin.aichat.rag.domain.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

public final class JdbcRagQuestionRepository implements RagQuestionRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    public JdbcRagQuestionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public List<RagQuestion.Source> search(String owner, UUID index, float[] embedding, int topK) {
        if (embedding.length != 768 || topK < 1 || topK > 50) throw new EmbeddingModel.Failure("INVALID_EMBEDDING");
        var vector = new StringJoiner(",", "[", "]");
        double norm = 0;
        for (float value : embedding) {
            if (!Float.isFinite(value)) throw new EmbeddingModel.Failure("INVALID_EMBEDDING");
            norm += (double) value * value; vector.add(Float.toString(value));
        }
        if (norm == 0) throw new EmbeddingModel.Failure("INVALID_EMBEDDING");
        return jdbc.query("""
                SELECT c.*, 1-(c.embedding OPERATOR(public.<=>) ?::public.vector) AS similarity,
                    (c.metadata->>'pageStart')::integer AS page_start, (c.metadata->>'pageEnd')::integer AS page_end
                FROM rag.chunks c JOIN rag.index_runs r ON r.id=c.run_id AND r.owner_username=c.owner_username
                WHERE c.owner_username=? AND c.run_id=? AND r.status='COMPLETED'
                    AND c.embedding IS NOT NULL AND public.vector_norm(c.embedding)>0
                ORDER BY c.embedding OPERATOR(public.<=>) ?::public.vector, c.ordinal LIMIT ?
                """, (rs, row) -> new RagQuestion.Source(row + 1,
                new DocumentChunk(rs.getObject("id", UUID.class), rs.getInt("ordinal"), rs.getString("source"),
                        rs.getString("title"), rs.getString("section"), rs.getObject("page_start", Integer.class),
                        rs.getObject("page_end", Integer.class), rs.getInt("start_offset"), rs.getInt("end_offset"),
                        rs.getString("content"), rs.getInt("token_count")), rs.getDouble("similarity")),
                vector.toString(), owner, index, vector.toString(), topK);
    }
    @Override public boolean create(String owner, RagQuestion question) {
        return jdbc.update("INSERT INTO rag.questions(id,owner_username,index_id,payload) VALUES(?,?,?,?::jsonb) ON CONFLICT(id) DO NOTHING",
                question.id(), owner, question.indexId(), json.writeValueAsString(question)) == 1;
    }
    @Override public List<RagQuestion.Source> searchAll(String owner,String model,float[] embedding,int topK) {
        if(embedding.length!=768 || topK<1 || topK>50) throw new EmbeddingModel.Failure("INVALID_EMBEDDING");
        var vector=new StringJoiner(",","[","]");double norm=0;
        for(float value:embedding) {
            if(!Float.isFinite(value)) throw new EmbeddingModel.Failure("INVALID_EMBEDDING");
            norm+=(double)value*value;vector.add(Float.toString(value));
        }
        if(norm==0) throw new EmbeddingModel.Failure("INVALID_EMBEDDING");
        return jdbc.query("""
                WITH active AS (
                  SELECT DISTINCT ON(document_id) id FROM rag.index_runs
                  WHERE owner_username=? AND status='COMPLETED'
                    AND regexp_replace(embedding_model,':latest$','')=regexp_replace(?,':latest$','')
                  ORDER BY document_id,(strategy='STRUCTURAL') DESC,created_at DESC,id DESC
                )
                SELECT c.*,1-(c.embedding OPERATOR(public.<=>) ?::public.vector) AS similarity,
                  (c.metadata->>'pageStart')::integer AS page_start,(c.metadata->>'pageEnd')::integer AS page_end
                FROM rag.chunks c JOIN active a ON a.id=c.run_id
                WHERE c.owner_username=? AND c.embedding IS NOT NULL AND public.vector_norm(c.embedding)>0
                ORDER BY c.embedding OPERATOR(public.<=>) ?::public.vector,c.id LIMIT ?
                """,(rs,n)->new RagQuestion.Source(n+1,new DocumentChunk(rs.getObject("id",UUID.class),rs.getInt("ordinal"),
                    rs.getString("source"),rs.getString("title"),rs.getString("section"),rs.getObject("page_start",Integer.class),
                    rs.getObject("page_end",Integer.class),rs.getInt("start_offset"),rs.getInt("end_offset"),
                    rs.getString("content"),rs.getInt("token_count")),rs.getDouble("similarity")),owner,model,vector.toString(),owner,vector.toString(),topK);
    }
    @Override public void save(String owner, RagQuestion question) {
        if (jdbc.update("UPDATE rag.questions SET payload=?::jsonb WHERE owner_username=? AND id=?",
                json.writeValueAsString(question), owner, question.id()) != 1) throw RagFailure.storage();
    }
    @Override public Optional<RagQuestion> find(String owner, UUID id) {
        return jdbc.query("SELECT payload FROM rag.questions WHERE owner_username=? AND id=?",
                (rs, row) -> json.readValue(rs.getString(1), RagQuestion.class), owner, id).stream().findFirst();
    }
    @Override public List<RagQuestion> list(String owner, UUID index) {
        return jdbc.query("SELECT payload FROM rag.questions WHERE owner_username=? AND index_id=? ORDER BY created_at DESC LIMIT 30",
                (rs, row) -> json.readValue(rs.getString(1), RagQuestion.class), owner, index);
    }
    @Override public void recoverInterrupted() {
        jdbc.update("UPDATE rag.questions SET payload=jsonb_set(payload,'{status}','\"INTERRUPTED\"'::jsonb) WHERE payload->>'status'='RUNNING'");
    }
}
