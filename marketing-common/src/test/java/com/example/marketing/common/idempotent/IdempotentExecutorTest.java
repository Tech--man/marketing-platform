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
}
