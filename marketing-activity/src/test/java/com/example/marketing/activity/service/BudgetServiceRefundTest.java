package com.example.marketing.activity.service;

import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import com.example.marketing.activity.infrastructure.mapper.ActivityMapper;
import com.example.marketing.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退款链路（2026-10-01 从 BudgetServiceTest 迁来并升级为真 H2）。
 *
 * <p>refund 的占位写入从 INSERT IGNORE 改为普通 INSERT + DuplicateKeyException 后，
 * 退款路径不再有 MySQL 方言，流水/配对/回放/used_amount 全部跑真 SQL；
 * 只有 Redis 侧（refund_budget.lua 的 EVAL）是桩——Lua 的封顶语义由
 * scripts/lua-contract.sh 对真 Redis 断言，Java 侧这里钉的是"传参正确 + 编排顺序"。</p>
 */
class BudgetServiceRefundTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private BudgetService service;
    /** 每次 EVAL 收到的 ARGV（金额、退后期望），由桩记录，断言在测试体做 */
    private final List<Object[]> evalArgv = new ArrayList<>();

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("classpath:schema-budget-h2.sql")
                .build();
        jdbc = new JdbcTemplate(db);
        // activity 真行：refund 末段的 used_amount 回减是 UPDATE，mapper 桩只喂
        // computeRemainCents 的 selectOne，不落库——没有这行，回减就是 0 行生效
        jdbc.update("INSERT INTO activity (activity_no, name, status, budget_amount, used_amount, version) "
                        + "VALUES (?,?,?,?,?,?)",
                "ACT2026001", "测试活动", "ONLINE",
                new BigDecimal("100.00"), new BigDecimal("30.00"), 0);
        redis = mock(StringRedisTemplate.class);
        service = new BudgetService(redis, jdbc, mapperReturning("100.00"));
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    /** 只拦 selectOne 的 Mapper 桩（与 BudgetServiceTest 同款） */
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

    /** 桩 refund_budget.lua 的 EVAL：记录 ARGV 并返回给定实退金额 */
    private void stubEval(Long effective) {
        when(redis.execute(
                org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                anyList(),
                org.mockito.ArgumentMatchers.any(Object[].class)))
                .thenAnswer(inv -> {
                    Object[] all = inv.getArguments();
                    evalArgv.add(java.util.Arrays.copyOfRange(all, all.length - 2, all.length));
                    return effective;
                });
    }

    private void flow(String bizKey, long amountCents, String type) {
        jdbc.update("INSERT INTO budget_flow (activity_no, biz_key, amount_cents, type) VALUES (?,?,?,?)",
                "ACT2026001", bizKey, amountCents, type);
    }

    @Test
    @DisplayName("A3：配对原 DEDUCT、落 REFUND 流水、把金额与按公式算出的退后期望传给 Lua")
    void refundPairsDeductAndPassesFormulaToEval() {
        flow("bk-1", -3_000L, "DEDUCT");
        stubEval(500L);

        assertEquals(BudgetService.RefundOutcome.REFUNDED, service.refund("ACT2026001", "bk-1", 500L));

        // 公式（此刻 REFUND 流水已落）：10000 - 3000 + 500 = 7500 ——传错公式等于
        // 把封顶判定建立在错误基线上，Lua 侧再对也拦不住多退
        assertEquals(1, evalArgv.size(), "退款恰好触发一次 EVAL");
        assertEquals("500", evalArgv.get(0)[0]);
        assertEquals("7500", evalArgv.get(0)[1]);
        // 真 SQL 断言：REFUND 行落了、used_amount 回减了（旧 mock 版本验不了这两件事；
        // 30.00 - 5.00 = 25.00）
        assertEquals(500L, jdbc.queryForObject(
                "SELECT amount_cents FROM budget_flow WHERE biz_key = 'refund:bk-1'", Long.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT used_amount FROM activity WHERE activity_no = 'ACT2026001'", BigDecimal.class)
                .compareTo(new BigDecimal("25.00")));
    }

    @Test
    @DisplayName("A3：同一笔扣减重复退款 → REPLAYED，不触发退款 EVAL")
    void refundReplaySkipsEval() {
        flow("bk-1", -3_000L, "DEDUCT");
        flow("refund:bk-1", 500L, "REFUND"); // 已退过
        stubEval(500L);

        assertEquals(BudgetService.RefundOutcome.REPLAYED, service.refund("ACT2026001", "bk-1", 500L));

        assertTrue(evalArgv.isEmpty(), "回放不该再碰 Redis 余额");
    }

    @Test
    @DisplayName("W2.2：Lua 返回 0（Redis 高于期望，孤儿流水）→ 仍 REFUNDED，传参仍是全款与公式值")
    void refundCappedToZeroStillCommitsFlow() {
        flow("bk-orphan", -3_000L, "DEDUCT");
        stubEval(0L); // Lua 判定：min(500, max(0, 7500-7600)) = 0

        assertEquals(BudgetService.RefundOutcome.REFUNDED,
                service.refund("ACT2026001", "bk-orphan", 500L));

        // 封顶发生在 Lua 内——Java 不该自作主张改小传参（改小会让对账口径漂移）
        assertEquals(1, evalArgv.size());
        assertEquals("500", evalArgv.get(0)[0]);
        assertEquals("7500", evalArgv.get(0)[1]);
    }

    @Test
    @DisplayName("A3：没有配对 DEDUCT 的退款 → 40400，不落任何流水")
    void refundWithoutDeductRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service.refund("ACT2026001", "bk-none", 500L));
        assertEquals(40400, e.getCode());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_flow", Integer.class));
    }

    @Test
    @DisplayName("A3：退款超过原扣减 → 40000")
    void refundOverDeductRejected() {
        flow("bk-small", -300L, "DEDUCT");

        BizException e = assertThrows(BizException.class,
                () -> service.refund("ACT2026001", "bk-small", 301L));
        assertEquals(40000, e.getCode());
    }
}
