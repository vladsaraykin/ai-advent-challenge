package com.github.vladsaraykin.aichat.review.infrastructure;

import com.github.vladsaraykin.aichat.agent.domain.ChatMessage;
import com.github.vladsaraykin.aichat.rag.domain.RagQuestion;
import com.github.vladsaraykin.aichat.review.application.ReviewRepository;
import com.github.vladsaraykin.aichat.review.domain.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

public class JdbcReviewRepository implements ReviewRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    public JdbcReviewRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void recover() {
        jdbc.update("UPDATE rag.code_reviews SET status='INTERRUPTED',error_code='restart',updated_at=now() WHERE status='RUNNING'");
    }
    public boolean begin(String owner, ReviewRequest request, String sha) {
        return jdbc.update("INSERT INTO rag.code_reviews(owner_username,id,payload_sha256,request_json,status) "
                + "VALUES(?,?,?,?::jsonb,'RUNNING') ON CONFLICT DO NOTHING", owner, request.requestId(), sha,
                json.writeValueAsString(request)) == 1;
    }
    public Optional<Entry> find(String owner, UUID id) {
        return jdbc.query("SELECT payload_sha256,status,result_json FROM rag.code_reviews WHERE owner_username=? AND id=?",
                (rs, row) -> new Entry(rs.getString(1), rs.getString(2), rs.getString(3) == null ? null
                        : json.readValue(rs.getString(3), CodeReview.class)), owner, id).stream().findFirst();
    }
    public void sources(String owner, UUID id, List<RagQuestion.Source> sources) {
        jdbc.update("UPDATE rag.code_reviews SET sources_json=?::jsonb,updated_at=now() WHERE owner_username=? AND id=?",
                json.writeValueAsString(sources), owner, id);
    }
    public void usage(String owner, UUID id, ChatMessage.Metrics metrics) {
        boolean incomplete = metrics == null || Boolean.FALSE.equals(metrics.usageAvailable())
                || (metrics.promptTokens() == 0 && metrics.completionTokens() == 0);
        jdbc.update("UPDATE rag.code_reviews SET metrics_json=?::jsonb,usage_incomplete=?,updated_at=now() WHERE owner_username=? AND id=?",
                json.writeValueAsString(metrics), incomplete, owner, id);
    }
    public void complete(String owner, CodeReview result) {
        jdbc.update("UPDATE rag.code_reviews SET status='COMPLETED',result_json=?::jsonb,updated_at=now() WHERE owner_username=? AND id=?",
                json.writeValueAsString(result), owner, result.id());
    }
    public void fail(String owner, UUID id, String code) {
        jdbc.update("UPDATE rag.code_reviews SET status='FAILED',error_code=?,updated_at=now() WHERE owner_username=? AND id=?",
                code, owner, id);
    }
}
