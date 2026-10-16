package com.github.vladsaraykin.aichat.review;

import com.github.vladsaraykin.aichat.review.infrastructure.JdbcReviewRepository;
import com.github.vladsaraykin.aichat.review.domain.CodeReview;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.assertj.core.api.Assertions.*;

/** Disposable PostgreSQL only: no real provider calls, no production database. */
@EnabledIfEnvironmentVariable(named = "REVIEW_TEST_DATABASE_URL", matches = ".+")
class JdbcReviewRepositoryPostgresTest {
    @Test void persistsReviewAndUsageWithOwnerIsolationIdempotencyAndRestartRecovery() throws Exception {
        var ds = new DriverManagerDataSource(System.getenv("REVIEW_TEST_DATABASE_URL"), "review_test", "review_test");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS rag");
        if (Boolean.FALSE.equals(jdbc.queryForObject("SELECT to_regclass('rag.code_reviews') IS NOT NULL", Boolean.class)))
            try (var connection = ds.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/rag/migration/V7__code_reviews.sql"));
            }
        var repository = new JdbcReviewRepository(jdbc);
        String owner = "review-test-" + UUID.randomUUID();
        var request = CodeReviewServiceTest.request();
        var interrupted = CodeReviewServiceTest.request();
        try {
            assertThat(repository.begin(owner, request, "a".repeat(64))).isTrue();
            assertThat(repository.begin(owner, request, "a".repeat(64))).isFalse();
            assertThat(repository.find("someone-else", request.requestId())).isEmpty();
            repository.sources(owner, request.requestId(), List.of(CodeReviewServiceTest.SOURCE));
            repository.usage(owner, request.requestId(), null);
            var result = new CodeReview(request.requestId(), request.repository(), 1, request.baseSha(), request.headSha(), List.of(),
                    List.of(CodeReviewServiceTest.SOURCE), null, true, "A review");
            repository.complete(owner, result);
            assertThat(new JdbcReviewRepository(jdbc).find(owner, request.requestId()).orElseThrow().result()).isEqualTo(result);
            assertThat(jdbc.queryForObject("SELECT usage_incomplete FROM rag.code_reviews WHERE owner_username=? AND id=?", Boolean.class,
                    owner, request.requestId())).isTrue();
            repository.begin(owner, interrupted, "b".repeat(64));
            repository.recover();
            assertThat(repository.find(owner, interrupted.requestId()).orElseThrow().status()).isEqualTo("INTERRUPTED");
            assertThat(repository.find(owner, request.requestId()).orElseThrow().status()).isEqualTo("COMPLETED");
            repository.fail(owner, interrupted.requestId(), "fixture_failure");
            assertThat(repository.find(owner, interrupted.requestId()).orElseThrow().status()).isEqualTo("FAILED");
        } finally { jdbc.update("DELETE FROM rag.code_reviews WHERE owner_username=?", owner); }
    }
}
