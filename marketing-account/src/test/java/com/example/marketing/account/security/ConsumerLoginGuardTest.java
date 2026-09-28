package com.example.marketing.account.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 限速边界的"是 > 还是 >="。 */
class ConsumerLoginGuardTest {

    @Test
    @DisplayName("取到 null（Redis 不可用）按放行：宁可少挡一次，不把真实用户关在门外")
    void nullCountFailsOpen() {
        assertTrue(ConsumerLoginGuard.allows(null, 5));
    }

    @Test
    @DisplayName("恰好等于限额仍放行，超一个才拦")
    void boundaryIsInclusive() {
        assertTrue(ConsumerLoginGuard.allows(5L, 5));
        assertFalse(ConsumerLoginGuard.allows(6L, 5));
    }

    @Test
    @DisplayName("limit<=0 的关闭语义由调用方处理，这里不解释成「全拦」")
    void zeroLimitIsHandledUpstream() {
        // allows(1, 0) 为 false 是事实，但 check() 在 limit<=0 时根本不会走到这里
        assertFalse(ConsumerLoginGuard.allows(1L, 0));
    }
}
