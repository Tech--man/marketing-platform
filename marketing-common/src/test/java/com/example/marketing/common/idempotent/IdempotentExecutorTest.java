package com.example.marketing.common.idempotent;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等执行器语义验证（H2 内存库模拟 idempotent_record 表）。
 */
class IdempotentExecutorTest {

    private JdbcTemplate jdbcTemplate;
    private IdempotentExecutor executor;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:idem_test;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        jdbcTemplate = new JdbcTemplate(ds);
        jdbcTemplate.execute("DROP TABLE IF EXISTS idempotent_record");
        jdbcTemplate.execute("""
                CREATE TABLE idempotent_record (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    biz_key VARCHAR(128) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    result_json TEXT,
                    error_msg VARCHAR(512),
                    claim_token VARCHAR(36) NULL,
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uk_biz_key UNIQUE (biz_key)
                )""");
        executor = new IdempotentExecutor(jdbcTemplate);
    }

    @Test
    @DisplayName("首次执行真实业务，重复请求回放缓存结果且不再执行业务")
    void firstExecuteThenReplay() {
        AtomicInteger calls = new AtomicInteger();
        String r1 = executor.execute("REQ-001", String.class, () -> "ORDER-" + calls.incrementAndGet());
        String r2 = executor.execute("REQ-001", String.class, () -> "ORDER-" + calls.incrementAndGet());

        assertEquals("ORDER-1", r1);
        assertEquals("ORDER-1", r2, "重复请求应回放首次结果");
        assertEquals(1, calls.get(), "业务动作只应执行一次");
        assertTrue(executor.isDone("REQ-001"));
    }

    @Test
    @DisplayName("处理中的请求重复进入时抛 DUPLICATE_REQUEST")
    void processingRejectsDuplicate() {
        // 手工插入一条 PROCESSING 记录模拟"另一实例正在处理"
        jdbcTemplate.update("INSERT INTO idempotent_record (biz_key, status) VALUES (?, ?)",
                "REQ-002", IdempotentStatus.PROCESSING.name());

        BizException e = assertThrows(BizException.class,
                () -> executor.execute("REQ-002", String.class, () -> "never"));
        assertEquals(ErrorCode.DUPLICATE_REQUEST.getCode(), e.getCode());
    }

    @Test
    @DisplayName("业务失败标记 FAILED，同键可重新抢占执行")
    void failedKeyCanBeRetried() {
        assertThrows(IllegalStateException.class,
                () -> executor.execute("REQ-003", String.class, () -> {
                    throw new IllegalStateException("boom");
                }));
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM idempotent_record WHERE biz_key = ?", String.class, "REQ-003");
        assertEquals(IdempotentStatus.FAILED.name(), status);

        String ok = executor.execute("REQ-003", String.class, () -> "SUCCESS-ON-RETRY");
        assertEquals("SUCCESS-ON-RETRY", ok);
        assertTrue(executor.isDone("REQ-003"));
    }

    @Test
    @DisplayName("executeVoid 成功后回放不重复执行")
    void voidExecutionIsIdempotent() {
        AtomicInteger calls = new AtomicInteger();
        executor.executeVoid("REQ-004", calls::incrementAndGet);
        executor.executeVoid("REQ-004", calls::incrementAndGet);
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("H11：PROCESSING 停留超过租约 → 视为执行者已死，重试接管执行（崩溃自愈）")
    void expiredProcessingLeaseIsReclaimed() {
        // 模拟崩溃现场：占键后进程死掉，记录永远停在 PROCESSING 且 update_time 已陈旧
        jdbcTemplate.update(
                "INSERT INTO idempotent_record (biz_key, status, update_time) VALUES (?, ?, "
                        + "DATEADD('SECOND', -180, CURRENT_TIMESTAMP))",
                "REQ-005", IdempotentStatus.PROCESSING.name());
        // 用 120s 租约的执行器：180s 前的 PROCESSING 必须能被接管
        IdempotentExecutor reclaiming = new IdempotentExecutor(jdbcTemplate, 120);

        String result = reclaiming.execute("REQ-005", String.class, () -> "RECLAIMED");

        assertEquals("RECLAIMED", result, "过期租约必须可被重试接管，而不是永远 DUPLICATE_REQUEST");
        assertEquals(IdempotentStatus.SUCCESS.name(), jdbcTemplate.queryForObject(
                "SELECT status FROM idempotent_record WHERE biz_key = ?", String.class, "REQ-005"));
    }

    @Test
    @DisplayName("H11：租约内的 PROCESSING 照旧拒绝（活着的执行者不被打断）")
    void freshProcessingStillRejects() {
        jdbcTemplate.update("INSERT INTO idempotent_record (biz_key, status) VALUES (?, ?)",
                "REQ-006", IdempotentStatus.PROCESSING.name());
        IdempotentExecutor reclaiming = new IdempotentExecutor(jdbcTemplate, 120);

        BizException e = assertThrows(BizException.class,
                () -> reclaiming.execute("REQ-006", String.class, () -> "never"));
        assertEquals(ErrorCode.DUPLICATE_REQUEST.getCode(), e.getCode());
    }

    @Test
    @DisplayName("P2：action 成功但 markSuccess 写库失败 → 不误标 FAILED，结果照常返回")
    void markSuccessFailureKeepsProcessingAndReturnsResult() {
        // 用对 SUCCESS 写路径抛错的 JdbcTemplate 包装模拟写失败：
        // markSuccess 的 SQL 特征是 SET ... result_json（状态值走 ? 参数，不在 SQL 文本里）
        org.springframework.jdbc.core.JdbcTemplate failing = new JdbcTemplate(
                jdbcTemplate.getDataSource()) {
            @Override
            public int update(String sql, Object... args) {
                if (sql.contains("result_json")) {
                    throw new org.springframework.dao.DataAccessResourceFailureException("db down");
                }
                return super.update(sql, args);
            }
        };
        IdempotentExecutor broken = new IdempotentExecutor(failing, 120);

        // action 成功 + markSuccess 炸：结果必须返回给调用方（预扣/消息都已完成），
        // 状态留在 PROCESSING（租约接管路径处理）——绝不能 markFailed 把它判成可重试
        String result = broken.execute("REQ-007", String.class, () -> "DONE");

        assertEquals("DONE", result, "action 已成功，结果必须交还调用方");
        assertEquals(IdempotentStatus.PROCESSING.name(),
                jdbcTemplate.queryForObject(
                        "SELECT status FROM idempotent_record WHERE biz_key = ?", String.class, "REQ-007"),
                "markSuccess 失败时标 FAILED = 已成功的动作被放开重试闸（领券即二次预扣）");
    }

    /**
     * W1.1 fencing 回归（2026-09-30 第二轮复审，变体 b）：慢持有者的动作超过租约被接管后，
     * 它的 markFailed 绝不能命中接管者的 PROCESSING——否则行被打成 FAILED 放开第三次执行，
     * 且接管者的 markSuccess 随后落空，一笔成功被记成失败（领券：二次预扣 + 用户一张拿不到）。
     *
     * <p>三 latch 控制确定性时序：t1 claim(token1) 后把行拨成过期；t2 接管(token2) 进入
     * action 但尚未 markSuccess——此刻行是 PROCESSING/token2；t1 恢复并失败，markFailed
     * 必须落空（新实现比对 token）；随后 t2 完成，markSuccess 命中 → 行 SUCCESS。</p>
     */
    @Test
    @DisplayName("W1.1：慢持有者被接管、接管者尚未落定时，其 markFailed 不得毒化接管者")
    void staleClaimantCannotPoisonTakeover() throws Exception {
        IdempotentExecutor shortLease = new IdempotentExecutor(jdbcTemplate, 1); // 租约 1s，便于制造过期
        java.util.concurrent.CountDownLatch t1Claimed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch t2InAction = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch t1Settled = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> t1Error = new java.util.concurrent.atomic.AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try {
                shortLease.execute("REQ-FENCE", String.class, () -> {
                    // 模拟"执行超过了租约"：把自己行的 update_time 拨回 60s 前
                    jdbcTemplate.update(
                            "UPDATE idempotent_record SET update_time = ? WHERE biz_key = ?",
                            java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(60)),
                            "REQ-FENCE");
                    t1Claimed.countDown();
                    try {
                        assertTrue(t2InAction.await(5, java.util.concurrent.TimeUnit.SECONDS),
                                "t2 应在超时前接管并进入 action");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("slow action finally failed");
                });
            } catch (Throwable e) {
                t1Error.set(e);
            } finally {
                // execute 的 catch（含 markFailed）已跑完，此刻 t1 对行的全部写影响已发生
                t1Settled.countDown();
            }
        }, "fencing-stale");
        t1.start();
        assertTrue(t1Claimed.await(5, java.util.concurrent.TimeUnit.SECONDS), "t1 应先完成 claim");

        // t2：读到过期租约 → 接管（换发 token2）→ action 挂起，等 t1 的失败回写先落地
        java.util.concurrent.atomic.AtomicReference<Object> t2Result = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t2 = new Thread(() -> t2Result.set(
                shortLease.execute("REQ-FENCE", String.class, () -> {
                    t2InAction.countDown();
                    try {
                        assertTrue(t1Settled.await(5, java.util.concurrent.TimeUnit.SECONDS),
                                "t1 的 markFailed 应在超时前完成");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "TAKEOVER";
                })), "fencing-takeover");
        t2.start();

        t1.join(5000);
        t2.join(5000);

        assertNotNull(t1Error.get(), "t1 的动作失败必须向上抛（调用方拿到失败）");
        assertEquals("TAKEOVER", t2Result.get(), "t2 接管者应正常拿到自己的结果");
        // 核心断言：行最终是接管者的 SUCCESS——旧实现（markFailed 只判 PROCESSING）会把它
        // 打成 FAILED，随后 t2 的 markSuccess 落空，一笔成功被记成失败
        assertEquals(IdempotentStatus.SUCCESS.name(),
                jdbcTemplate.queryForObject(
                        "SELECT status FROM idempotent_record WHERE biz_key = ?", String.class, "REQ-FENCE"),
                "旧实现里 markFailed 的 WHERE 只判 PROCESSING——命中接管者把它打成 FAILED，放开第三次执行");
        assertEquals("\"TAKEOVER\"",
                jdbcTemplate.queryForObject(
                        "SELECT result_json FROM idempotent_record WHERE biz_key = ?", String.class, "REQ-FENCE"),
                "接管者写入的结果不被旧持有者覆盖");
    }
}
