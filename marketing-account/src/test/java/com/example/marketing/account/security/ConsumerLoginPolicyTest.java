package com.example.marketing.account.security;

import com.example.marketing.account.infrastructure.entity.ConsumerUserEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 登录判定的边界。顺序错了就是安全语义错了，所以逐条钉住。 */
class ConsumerLoginPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 25, 12, 0);

    private static ConsumerUserEntity user(String status, LocalDateTime lockUntil, int failCount) {
        ConsumerUserEntity u = new ConsumerUserEntity();
        u.setId(70001L);
        u.setIdentifier("demo");
        u.setStatus(status);
        u.setLockUntil(lockUntil);
        u.setFailCount(failCount);
        return u;
    }

    @Test
    @DisplayName("null 用户按口令错处理：不告诉调用方「这个账号不存在」")
    void unknownUserLooksLikeBadCredentials() {
        assertEquals(ConsumerLoginPolicy.Verdict.BAD_CREDENTIALS,
                ConsumerLoginPolicy.evaluate(null, false, NOW));
        // 口令"对"也不放行：没有账号就没有对，别让枚举者靠这个分支分辨
        assertEquals(ConsumerLoginPolicy.Verdict.BAD_CREDENTIALS,
                ConsumerLoginPolicy.evaluate(null, true, NOW));
    }

    @Test
    @DisplayName("停用先于口令判定：禁用账号不能因为口令对就进来")
    void disabledBeatsCredentials() {
        assertEquals(ConsumerLoginPolicy.Verdict.DISABLED,
                ConsumerLoginPolicy.evaluate(user("DISABLED", null, 0), true, NOW));
    }

    @Test
    @DisplayName("锁定先于口令判定：否则一次拒绝等于送一个口令校验 oracle")
    void lockedBeatsCredentials() {
        ConsumerUserEntity locked = user("ACTIVE", NOW.plusMinutes(5), 0);
        assertEquals(ConsumerLoginPolicy.Verdict.LOCKED,
                ConsumerLoginPolicy.evaluate(locked, true, NOW));
    }

    @Test
    @DisplayName("锁定时刻已过则不再算锁定")
    void lockExpiresNaturally() {
        ConsumerUserEntity past = user("ACTIVE", NOW.minusSeconds(1), 0);
        assertEquals(ConsumerLoginPolicy.Verdict.PASS,
                ConsumerLoginPolicy.evaluate(past, true, NOW));
    }

    @Test
    @DisplayName("未到阈值只累加计数，不动已有的 lockUntil")
    void belowThresholdJustCounts() {
        LocalDateTime existing = NOW.plusMinutes(3);
        ConsumerUserEntity u = user("ACTIVE", existing, 2);
        ConsumerLoginPolicy.FailureState s = ConsumerLoginPolicy.onBadCredentials(u, 5, 15, NOW);
        assertEquals(3, s.failCount());
        assertEquals(existing, s.lockUntil(), "未到阈值不该改锁");
    }

    @Test
    @DisplayName("到阈值：上锁 15 分钟且计数清零 —— 不清零等于解锁后一次失败就永久锁定")
    void thresholdLocksAndResets() {
        ConsumerUserEntity u = user("ACTIVE", null, 4);
        ConsumerLoginPolicy.FailureState s = ConsumerLoginPolicy.onBadCredentials(u, 5, 15, NOW);
        assertEquals(0, s.failCount());
        assertNotNull(s.lockUntil());
        assertEquals(NOW.plusMinutes(15), s.lockUntil());
    }

    @Test
    @DisplayName("从未失败过的账号第一次失败不锁定")
    void firstFailureDoesNotLock() {
        ConsumerUserEntity u = user("ACTIVE", null, 0);
        ConsumerLoginPolicy.FailureState s = ConsumerLoginPolicy.onBadCredentials(u, 5, 15, NOW);
        assertEquals(1, s.failCount());
        assertNull(s.lockUntil());
    }
}
