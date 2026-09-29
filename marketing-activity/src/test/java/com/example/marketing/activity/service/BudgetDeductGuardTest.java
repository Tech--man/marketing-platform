package com.example.marketing.activity.service;

import com.example.marketing.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H10 回归（2026-09-29 架构审查）：C 端预算扣减的两道滥用闸。
 *
 * <p>收口前 {@code POST /api/activity/{no}/budget/deduct} 无身份、无频控、无单笔上限，
 * bizKey 又由调用方自报——换键即真扣，一个登录账号能把活动预算逐笔抽光且流水全部"合法"。</p>
 */
class BudgetDeductGuardTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final BudgetDeductGuard guard = new BudgetDeductGuard(redis, 3, 60, 100_000L);

    /** 记录本次计数键，供"维度隔离"用例断言 */
    private String lastKey;

    @SuppressWarnings("unchecked")
    private void windowCount(long count) {
        when(redis.execute(any(RedisScript.class), anyList(), eq("60"))).thenAnswer(inv -> {
            lastKey = ((java.util.List<String>) inv.getArgument(1)).get(0);
            return count;
        });
    }

    @Test
    @DisplayName("窗口内超次 → 42900：与网关限流同码，客户端处理动作一致")
    void overLimitRejected() {
        windowCount(4L); // 上限 3

        BizException e = assertThrows(BizException.class, () -> guard.checkRate("ACT1", 70001L));
        assertEquals(42900, e.getCode());
    }

    @Test
    @DisplayName("窗口内未超次 → 放行（正常用户不受这道闸影响）")
    void withinLimitPasses() {
        windowCount(3L);
        assertDoesNotThrow(() -> guard.checkRate("ACT1", 70001L));
    }

    @Test
    @DisplayName("Redis 计数不可用 → fail-open：预算硬闸仍在 BudgetService，别让闸自己变成故障点")
    @SuppressWarnings("unchecked")
    void redisFailureFailsOpen() {
        when(redis.execute(any(RedisScript.class), anyList(), eq("60")))
                .thenThrow(new RuntimeException("connection refused"));

        assertDoesNotThrow(() -> guard.checkRate("ACT1", 70001L));
    }

    @Test
    @DisplayName("单笔金额超上限 → 40000 并点名上限（纯校验，不碰 Redis）")
    void amountOverCapRejected() {
        BizException e = assertThrows(BizException.class, () -> guard.checkAmount(100_001L));
        assertEquals(40000, e.getCode());
        assertDoesNotThrow(() -> guard.checkAmount(100_000L));
    }

    @Test
    @DisplayName("计数键按 活动+用户 维度隔离：刷别人的活动不该吃自己的限频")
    void keyScopesToActivityAndUser() {
        windowCount(1L);

        guard.checkRate("ACT1", 70001L);

        assertEquals("mkt:budget:deduct:guard:ACT1:70001", lastKey,
                "键必须是 活动:用户 维度，出现别的形状就是维度串了");
    }
}
