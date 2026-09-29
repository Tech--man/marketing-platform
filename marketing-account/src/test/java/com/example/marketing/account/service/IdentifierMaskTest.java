package com.example.marketing.account.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B4-5 回归：identifier 日志脱敏——将来承载手机号/邮箱，明文进日志等于"删不掉的 PII"。
 */
class IdentifierMaskTest {

    @Test
    @DisplayName("常规长度：保留前 2 后 2，中间按长度打码")
    void masksMiddle() {
        // 11 位手机号 → 13 + 7 星 + 00
        assertEquals("13*******00", IdentifierMask.mask("13800138000"));
        // 8 位 → de + 4 星 + xa
        assertEquals("de****xa", IdentifierMask.mask("demo@exa"));
    }

    @Test
    @DisplayName("短 identifier 整段打码（保留过多等于没脱）")
    void shortIdentifierFullyMasked() {
        assertEquals("****", IdentifierMask.mask("demo"));
        assertEquals("****", IdentifierMask.mask(null));
    }

    @Test
    @DisplayName("长度 7 的边界：保留 4 打码 3")
    void lengthSevenBoundary() {
        assertEquals("ab***fg", IdentifierMask.mask("abcdefg"));
    }
}
