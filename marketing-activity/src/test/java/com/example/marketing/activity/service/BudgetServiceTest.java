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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
        return mapperReturning(budgetYuan, "ONLINE", null);
    }

    /** W2.4 变体：可指定状态与 endTime（deduct 参与闸的两种拒绝形态） */
    private static ActivityMapper mapperReturning(String budgetYuan, String status,
                                                  java.time.LocalDateTime endTime) {
        return (ActivityMapper) Proxy.newProxyInstance(
                ActivityMapper.class.getClassLoader(),
                new Class<?>[]{ActivityMapper.class},
                (proxy, method, args) -> {
                    if ("selectOne".equals(method.getName())) {
                        ActivityEntity entity = new ActivityEntity();
                        entity.setActivityNo("ACT2026001");
                        entity.setName("测试活动");
                        entity.setStatus(status);
                        entity.setEndTime(endTime);
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
    @DisplayName("H4：Redis 扣减抛异常 → 回删占位流水并抛 50000，不留超支窗口")
    void redisFailureRollsBackFlow() {
        // deduct 走的是 INSERT IGNORE（MySQL 方言），H2 单测里起不了真表，
        // 这里用 mock JdbcTemplate 只钉核心行为：占位插入成功后 Redis 抛异常，
        // 占位 DELETE 必须被调用、且必须带 activityNo+bizKey（地雷 B 的另一半）。
        org.springframework.data.redis.core.StringRedisTemplate redisDown =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.mockito.Mockito.when(redisDown.execute(
                        org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.<Object>any()))
                .thenThrow(new RuntimeException("connection refused"));
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        // 流水占位是 3 个绑定参数的 INSERT；回删是 2 个参数的 DELETE
        org.mockito.Mockito.when(jdbc.update(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        BudgetService broken = new BudgetService(redisDown, jdbc, mapperReturning("100.00"));

        BizException e = assertThrows(BizException.class,
                () -> broken.deduct("ACT2026001", 100L, "bk-redis-down"));

        assertEquals(50000, e.getCode());
        org.mockito.Mockito.verify(jdbc).update(
                org.mockito.ArgumentMatchers.contains("DELETE FROM budget_flow"),
                org.mockito.ArgumentMatchers.eq("ACT2026001"),
                org.mockito.ArgumentMatchers.eq("bk-redis-down"));
    }

    // 退款用例（配对/回放/封顶/全额）已迁往 BudgetServiceRefundTest（2026-10-01）：
    // refund 改普通 INSERT + DuplicateKeyException 后，退款路径可在 H2 上端到端跑
    // 真 SQL，不再依赖 mock JdbcTemplate 钉编排。

    @Test
    @DisplayName("A3：没有配对 DEDUCT 的退款 → 40400（退款不能凭空造钱）")
    void refundWithoutDeductRejected() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any()))
                .thenReturn(java.util.List.of());
        BudgetService refundable = new BudgetService(null, jdbc, mapperReturning("100.00"));

        BizException e = assertThrows(BizException.class,
                () -> refundable.refund("ACT2026001", "bk-none", 500L));
        assertEquals(40400, e.getCode());
    }

    @Test
    @DisplayName("A3：退款超过原扣减 → 40000")
    void refundOverDeductRejected() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any()))
                .thenReturn(java.util.List.of(java.util.Map.of("amount_cents", -3000)));
        BudgetService refundable = new BudgetService(null, jdbc, mapperReturning("100.00"));

        BizException e = assertThrows(BizException.class,
                () -> refundable.refund("ACT2026001", "bk-1", 3001L));
        assertEquals(40000, e.getCode());
    }

    @Test
    @DisplayName("第六批 W2：RE_ONLINE 预热走对账公式——ActivityService.transition 的调用方向")
    void reonlineWarmUsesReconciliation() {
        // ActivityService.transition 在 target==ONLINE 时调
        // budgetService.warmCentsIfAbsent(activityNo, computeRemainCents(activityNo))。
        // 有 DEDUCT 流水时 computeRemainCents < 全额——warmCentsIfAbsent 收到的是
        // 对账值而非 budget_amount（地雷 E 残留：全额会把已消耗的预算凭空回涨）。
        flow("reserve:bk-e", -3_000L, "DEDUCT");
        long reconciled = service.computeRemainCents("ACT2026001");

        assertEquals(7_000L, reconciled,
                "RE_ONLINE 预热必须用这个值（7_000），不是全额 10_000——用全额就是预算回涨");
    }

    @Test
    @DisplayName("注册进重预热表的类型标识是 budget，注册表按它分发")
    void registersAsBudgetType() {
        assertEquals("budget", service.type());
    }

    @Test
    @DisplayName("P1：缺键分支的 SET 失败 → 回删占位流水并抛 50000，不留孤儿 DEDUCT")
    void missingKeySetFailureRollsBackFlow() {
        // 缺键（evalDeduct 返回 -1）后对账写回（SET）撞 Redis 故障——原实现这一段
        // 在 try 之外：占位流水留存 + Redis 未扣，重试命中 REPLAYED 假成功，Redis
        // 恒高于权威值（超支方向，H4 同根因的孪生窗口）。
        org.springframework.data.redis.core.StringRedisTemplate redisDown =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.mockito.Mockito.when(redisDown.execute(
                        org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.<Object>any()))
                .thenReturn(-1L); // Lua 判缺键
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> ops =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(redisDown.opsForValue()).thenReturn(ops);
        org.mockito.Mockito.doThrow(new RuntimeException("connection refused"))
                .when(ops).set(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.update(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(1); // 占位 INSERT 成功
        // 对账查询（computeRemainCents 走 jdbc.queryForList）默认返回空列表 → 对账值 0
        BudgetService broken = new BudgetService(redisDown, jdbc, mapperReturning("100.00"));

        BizException e = assertThrows(BizException.class,
                () -> broken.deduct("ACT2026001", 100L, "bk-missing-key-set-down"));

        assertEquals(50000, e.getCode());
        org.mockito.Mockito.verify(jdbc).update(
                org.mockito.ArgumentMatchers.contains("DELETE FROM budget_flow"),
                org.mockito.ArgumentMatchers.eq("ACT2026001"),
                org.mockito.ArgumentMatchers.eq("bk-missing-key-set-down"));
    }

    @Test
    @DisplayName("W2.4：OFFLINE 活动的扣减被参与闸拒绝（41007），不落流水")
    void deductRejectedWhenActivityOffline() {
        BudgetService offline = new BudgetService(null, jdbc, mapperReturning("100.00", "OFFLINE", null));

        BizException e = assertThrows(BizException.class,
                () -> offline.deduct("ACT2026001", 100L, "bk-gate-1"));

        assertEquals(41007, e.getCode());
        Integer flows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_flow WHERE biz_key = 'bk-gate-1'", Integer.class);
        assertEquals(0, flows, "闸在流水占位之前——被拒的请求不留任何写痕迹");
    }

    @Test
    @DisplayName("W2.4：endTime 已过的活动扣减被拒（41007），不落流水")
    void deductRejectedWhenPastEndTime() {
        BudgetService expired = new BudgetService(null, jdbc,
                mapperReturning("100.00", "ONLINE", java.time.LocalDateTime.now().minusMinutes(1)));

        BizException e = assertThrows(BizException.class,
                () -> expired.deduct("ACT2026001", 100L, "bk-gate-2"));

        assertEquals(41007, e.getCode());
        Integer flows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_flow WHERE biz_key = 'bk-gate-2'", Integer.class);
        assertEquals(0, flows);
    }
}
