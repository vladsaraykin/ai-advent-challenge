package com.github.vladsaraykin.aichat.harness.infrastructure;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeStatsRepository {
    private final JdbcTemplate jdbc;
    public KnowledgeStatsRepository(@Qualifier("ragJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Stats(long documents, long completedIndexes, long chunks, long fileBytes) { }
    public Stats get(String owner) {
        return new Stats(count("SELECT count(*) FROM rag.documents WHERE owner_username=?", owner),
                count("SELECT count(*) FROM rag.index_runs WHERE owner_username=? AND status='COMPLETED'", owner),
                count("SELECT count(*) FROM rag.chunks WHERE owner_username=?", owner),
                count("SELECT coalesce(sum(byte_size),0) FROM rag.documents WHERE owner_username=?", owner));
    }
    private long count(String sql, String owner) { return jdbc.queryForObject(sql, Long.class, owner); }
}
