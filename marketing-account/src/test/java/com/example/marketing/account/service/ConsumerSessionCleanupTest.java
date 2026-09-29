package com.example.marketing.account.service;

import com.example.marketing.common.schedule.RedisLeaseLock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;

/**
 * B4-6 回归：会话清理按 refresh 寿命删过期行——删掉的行不可能再通过任何校验
 * （判定口径与 refresh() 相同），对正确性零影响；只清超过保留期的（给取证留窗口）。
 */
class ConsumerSessionCleanupTest {

    private org.springframework.jdbc.datasource.DriverManagerDataSource ds;
    private JdbcTemplate jdbc;
    private ConsumerSessionCleanup cleanup;

    @BeforeEach
    void setUp() {
        ds = new DriverManagerDataSource(
                "jdbc:h2:mem:session_cleanup_test;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS consumer_session");
        jdbc.execute("""
                CREATE TABLE consumer_session (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    jti VARCHAR(64) NOT NULL,
                    user_id BIGINT NOT NULL,
                    identifier VARCHAR(64) DEFAULT '',
                    refresh_hash CHAR(64) NOT NULL,
                    expire_at TIMESTAMP NOT NULL,
                    refresh_expire_at TIMESTAMP NOT NULL,
                    revoked_at TIMESTAMP NULL,
                    CONSTRAINT uk_jti UNIQUE (jti),
                    CONSTRAINT uk_refresh_hash UNIQUE (refresh_hash)
                )""");
        // 锁直通：单测只关心清理逻辑本身
        RedisLeaseLock lock = mock(RedisLeaseLock.class);
        org.mockito.Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(2)).run();
            return null;
        }).when(lock).runExclusive(anyString(), any(), any());
        cleanup = new ConsumerSessionCleanup(jdbc, lock, new SimpleMeterRegistry(), 7, 12);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TABLE IF EXISTS consumer_session");
    }

    private void session(String jti, LocalDateTime refreshExpireAt) {
        jdbc.update("INSERT INTO consumer_session (jti, user_id, refresh_hash, expire_at, refresh_expire_at) "
                        + "VALUES (?,?,?,?,?)",
                jti, 70001L, "hash-" + jti, refreshExpireAt, refreshExpireAt);
    }

    @Test
    @DisplayName("refresh 寿命早于保留期的行被删，仍在窗口内的不动")
    void purgesOnlyExpiredBeyondRetention() {
        LocalDateTime now = LocalDateTime.now();
        session("jti-old", now.minusDays(30));   // 过期 30 天 > 保留期 7 天 → 删
        session("jti-recent", now.minusDays(2)); // 刚过期 2 天，还在取证窗口 → 留
        session("jti-live", now.plusDays(29));   // 活会话 → 留

        cleanup.runOnce();

        assertEquals(0, count("jti-old"));
        assertEquals(1, count("jti-recent"), "保留期内的过期行留给取证，不能一过期就删");
        assertEquals(1, count("jti-live"));
    }

    @Test
    @DisplayName("B4-2 配套：被删行不可能再通过 refresh 校验（口径一致性）")
    void deletedRowsCouldNeverRefresh() {
        // 删除条件 refresh_expire_at < now-7d；refresh() 拒绝条件 refresh_expire_at <= now。
        // 前者严格强于后者，删掉的行必然早已被 refresh() 拒绝——清理零正确性影响。
        LocalDateTime now = LocalDateTime.now();
        session("jti-purge", now.minusDays(10));
        cleanup.runOnce();
        assertEquals(0, count("jti-purge"));
    }

    private int count(String jti) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM consumer_session WHERE jti=?",
                Integer.class, jti);
        return c == null ? 0 : c;
    }
}
