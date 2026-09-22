package com.example.marketing.admin.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 登录限速的边界与失败方向。 */
class LoginGuardTest {

    @Test
    @DisplayName("第 limit 次放行、第 limit+1 次才拒（含不等于超）")
    void boundaryIsInclusive() {
        assertTrue(LoginGuard.allows(1L, 10));
        assertTrue(LoginGuard.allows(10L, 10));
        assertFalse(LoginGuard.allows(11L, 10));
    }

    @Test
    @DisplayName("Redis 拿不到计数时放行：限速挂掉不能把管理员关在门外")
    void missingCountFailsOpen() {
        assertTrue(LoginGuard.allows(null, 10));
    }
}
