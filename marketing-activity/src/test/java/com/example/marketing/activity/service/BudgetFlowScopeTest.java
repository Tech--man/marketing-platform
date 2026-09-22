package com.example.marketing.activity.service;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 预算流水的键作用域（地雷 B）。
 *
 * <p>{@code /api/activity/{no}/budget/deduct} 的 bizKey 由调用方提供，而 budget_flow 的
 * 唯一索引原来是<b>全局</b>的：两个活动用同一个 bizKey，第二个被 INSERT IGNORE 静默吞掉，
 * 既不扣款也返回成功；而失败回滚 {@code DELETE ... WHERE biz_key = ?} 会删掉<b>另一个活动</b>
 * 的占位。唯一键必须是 (activity_no, biz_key)。</p>
 *
 * <p>这里只跑真 SQL（不碰 Redis）：要验的是"表与语句的作用域"，不是扣减算法。</p>
 */
class BudgetFlowScopeTest {

    private JdbcTemplate jdbc;
    private BudgetService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:budget_flow_test;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS budget_flow");
        jdbc.execute("""
                CREATE TABLE budget_flow (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    activity_no VARCHAR(64) NOT NULL,
                    biz_key VARCHAR(128) NOT NULL,
                    amount_cents BIGINT NOT NULL,
                    type VARCHAR(16) NOT NULL,
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uk_activity_biz UNIQUE (activity_no, biz_key)
                )""");
        // 只测 SQL 作用域，Redis 与活动表都不参与
        service = new BudgetService(null, jdbc, null);
    }

    private int insert(String activityNo, String bizKey, long cents) {
        return jdbc.update("INSERT IGNORE INTO budget_flow (activity_no, biz_key, amount_cents, type)"
                + " VALUES (?, ?, ?, 'DEDUCT')", activityNo, bizKey, cents);
    }

    @Test
    @DisplayName("同一个 bizKey 用在两个活动上，两次扣减都要真的记账")
    void sameBizKeyAcrossActivitiesBothPersist() {
        assertEquals(1, insert("ACT-A", "order-001", -100L));
        assertEquals(1, insert("ACT-B", "order-001", -200L),
                "不同活动同键不是重复请求，必须各记各的");

        assertEquals(-300L, jdbc.queryForObject(
                "SELECT SUM(amount_cents) FROM budget_flow", Long.class));
    }

    @Test
    @DisplayName("同活动同键仍然被拒（幂等语义不能因为改索引而丢掉）")
    void duplicateWithinActivityStillRejected() {
        assertEquals(1, insert("ACT-A", "order-001", -100L));
        assertEquals(0, insert("ACT-A", "order-001", -100L));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM budget_flow", Integer.class));
    }

    @Test
    @DisplayName("回滚只删本活动的占位，不许碰别的活动同 bizKey 的流水")
    void rollbackIsScopedToItsActivity() {
        insert("ACT-A", "order-001", -100L);
        insert("ACT-B", "order-001", -200L);

        service.rollbackFlow("ACT-A", "order-001");

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM budget_flow", Integer.class));
        assertEquals(-200L, jdbc.queryForObject("SELECT SUM(amount_cents) FROM budget_flow", Long.class),
                "ACT-B 的流水必须原样保留");
    }

    @Test
    @DisplayName("金额为正数以外的入参直接拒绝")
    void rejectsNonPositiveAmount() {
        assertThrows(BizException.class, () -> service.deduct("ACT-A", 0L, "order-001"));
        assertThrows(BizException.class, () -> service.deduct("ACT-A", -5L, "order-001"));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(),
                assertThrows(BizException.class, () -> service.deduct("ACT-A", 0L, "k")).getCode());
    }
}
