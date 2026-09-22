package com.example.marketing.activity.service;

import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import com.example.marketing.activity.infrastructure.mapper.ActivityMapper;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 预算重算口径（地雷 E 的回归锚）。
 *
 * <p>类注释写的对账口径是"Redis 剩余 == 总预算 - SUM(DEDUCT) + SUM(REFUND)"，
 * 而实现曾按 activity.budget_amount <b>全额</b>预热 —— 预算键一旦丢失（重建数据层、
 * 换形态、手工 DEL）余额就凭空回涨，是可以超支的资金问题。
 * 因此这里断言的是<b>公式</b>，跑真 H2 上的真 SQL。</p>
 *
 * <p>StringRedisTemplate 传 null：重算只允许依赖 DB 与流水，
 * 一旦哪天它偷偷去读 Redis，测试会以 NPE 立刻暴露。</p>
 */
class BudgetServiceTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private BudgetService service;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("classpath:schema-budget-h2.sql")
                .build();
        jdbc = new JdbcTemplate(db);
        service = new BudgetService(null, jdbc, mapperReturning("100.00"));
    }

    @AfterEach
    void tearDown() {
        // EmbeddedDatabase 不是 AutoCloseable，只能显式 shutdown
        db.shutdown();
    }

    /** 只拦 selectOne 的 Mapper 桩；BaseMapper 抽象方法太多，不值得手写全。 */
    private static ActivityMapper mapperReturning(String budgetYuan) {
        return (ActivityMapper) Proxy.newProxyInstance(
                ActivityMapper.class.getClassLoader(),
                new Class<?>[]{ActivityMapper.class},
                (proxy, method, args) -> {
                    if ("selectOne".equals(method.getName())) {
                        ActivityEntity entity = new ActivityEntity();
                        entity.setActivityNo("ACT2026001");
                        entity.setName("测试活动");
                        entity.setStatus("ONLINE");
                        entity.setBudgetAmount(new BigDecimal(budgetYuan));
                        return entity;
                    }
                    return null;
                });
    }

    private void flow(String bizKey, long amountCents, String type) {
        jdbc.update("INSERT INTO budget_flow (activity_no, biz_key, amount_cents, type) VALUES (?,?,?,?)",
                "ACT2026001", bizKey, amountCents, type);
    }

    @Test
    @DisplayName("没扣过时剩余等于总预算（分）")
    void fullBudgetWhenNothingSpent() {
        assertEquals(10_000L, service.computeRemainCents("ACT2026001"));
    }

    @Test
    @DisplayName("扣过 30 元后重算是 7000 分，而不是回涨成 10000")
    void subtractsDeductedFlows() {
        flow("grant:CT2026001:REQ-1", -3_000L, "DEDUCT");

        assertEquals(7_000L, service.computeRemainCents("ACT2026001"));
    }

    @Test
    @DisplayName("退款流水要加回余额")
    void addsBackRefundFlows() {
        flow("grant:CT2026001:REQ-1", -3_000L, "DEDUCT");
        flow("refund:CT2026001:REQ-1", 500L, "REFUND");

        assertEquals(7_500L, service.computeRemainCents("ACT2026001"));
    }

    @Test
    @DisplayName("只统计本活动的流水，别活动的扣减不能串进来")
    void ignoresOtherActivitiesFlows() {
        jdbc.update("INSERT INTO budget_flow (activity_no, biz_key, amount_cents, type) VALUES (?,?,?,?)",
                "ACT9999999", "grant:CT9:REQ-x", -9_000L, "DEDUCT");

        assertEquals(10_000L, service.computeRemainCents("ACT2026001"));
    }

    @Test
    @DisplayName("活动不存在要报错，不能当 0 预算预热出去")
    void unknownActivityFailsInsteadOfWarmingZero() {
        BudgetService noActivity = new BudgetService(null, jdbc,
                (ActivityMapper) Proxy.newProxyInstance(
                        ActivityMapper.class.getClassLoader(),
                        new Class<?>[]{ActivityMapper.class},
                        (p, m, a) -> null));

        BizException e = assertThrows(BizException.class, () -> noActivity.computeRemainCents("ACT0000000"));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), e.getCode(), "查不到资源不该是 41000");
    }

    @Test
    @DisplayName("注册进重预热表的类型标识是 budget，注册表按它分发")
    void registersAsBudgetType() {
        assertEquals("budget", service.type());
    }
}
