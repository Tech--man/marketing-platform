package com.example.marketing.admin.security;

import com.example.marketing.admin.infrastructure.entity.AdminUserEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 登录策略的边界。这四条都是"看起来能跑但出问题时最难查"的类型：
 * 账号不存在与口令错必须同结果（否则登录口就是用户名枚举接口）、停用账号不能被口令绕过、
 * 锁定期到点要自动放行（否则需要一个人工解锁任务）、以及"锁着"和"密码错"要能分开记账。
 */
class LoginPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 22, 12, 0);

    private static AdminUserEntity user(String status, Integer fails, LocalDateTime lockUntil) {
        AdminUserEntity u = new AdminUserEntity();
        u.setId(1L);
        u.setUsername("admin");
        u.setStatus(status);
        u.setFailCount(fails == null ? 0 : fails);
        u.setLockUntil(lockUntil);
        return u;
    }

    @Test
    @DisplayName("账号不存在与口令错同码，不给枚举留口子")
    void unknownUserAndWrongPasswordAreIndistinguishable() {
        assertEquals(LoginPolicy.Verdict.BAD_CREDENTIALS, LoginPolicy.evaluate(null, false, NOW));
        assertEquals(LoginPolicy.Verdict.BAD_CREDENTIALS,
                LoginPolicy.evaluate(user("ACTIVE", 0, null), false, NOW));
    }

    @Test
    @DisplayName("停用账号即使口令正确也拒，且原因与口令错不同")
    void disabledAccountIsRejectedBeforePassword() {
        assertEquals(LoginPolicy.Verdict.DISABLED,
                LoginPolicy.evaluate(user("DISABLED", 0, null), true, NOW));
        // 口令错也回 DISABLED：判定分支在口令之前，停用账号不会退化成"口令错"的普通拒绝，
        // 调用方据此可以直接给"账号已停用"，不必再猜是哪一种。
        assertEquals(LoginPolicy.Verdict.DISABLED,
                LoginPolicy.evaluate(user("DISABLED", 3, null), false, NOW));
    }

    @Test
    @DisplayName("锁定期内拒绝；到点自动放行，不需要人工解锁")
    void lockExpiresByItself() {
        var locked = user("ACTIVE", 5, NOW.plusMinutes(1));
        var justExpired = user("ACTIVE", 5, NOW.minusSeconds(1));

        assertEquals(LoginPolicy.Verdict.LOCKED, LoginPolicy.evaluate(locked, true, NOW));
        // 锁定期内不回"口令错"：一是给对/错口令在锁定态下留了个可区分的信号，等于
        // 白送一个口令校验 oracle；二是调用方按 LOCKED 不再累计失败数，锁不会被告警刷新的
        // 请求无限续期。
        assertEquals(LoginPolicy.Verdict.LOCKED, LoginPolicy.evaluate(locked, false, NOW));
        assertEquals(LoginPolicy.Verdict.PASS, LoginPolicy.evaluate(justExpired, true, NOW));
    }

    @Test
    @DisplayName("未被锁且口令正确才放行")
    void happyPath() {
        assertEquals(LoginPolicy.Verdict.PASS,
                LoginPolicy.evaluate(user("ACTIVE", 2, null), true, NOW));
    }

    @Test
    @DisplayName("未达阈值的失败只是累加，不锁人")
    void failuresAccumulateBelowThreshold() {
        var state = LoginPolicy.onBadCredentials(user("ACTIVE", 0, null), 5, 15, NOW);
        assertEquals(1, state.failCount());
        assertNull(state.lockUntil());
    }

    @Test
    @DisplayName("到达阈值即锁定，且计数清零 —— 不清零的话解锁后第一次失败又立刻锁回去")
    void thresholdLocksAndResetsCounter() {
        var state = LoginPolicy.onBadCredentials(user("ACTIVE", 4, null), 5, 15, NOW);
        assertEquals(0, state.failCount());
        assertEquals(NOW.plusMinutes(15), state.lockUntil());

        var lockedUser = user("ACTIVE", state.failCount(), state.lockUntil());
        assertEquals(LoginPolicy.Verdict.LOCKED, LoginPolicy.evaluate(lockedUser, false, NOW.plusMinutes(14)));
        assertEquals(LoginPolicy.Verdict.BAD_CREDENTIALS,
                LoginPolicy.evaluate(lockedUser, false, NOW.plusMinutes(16)));
    }
}
