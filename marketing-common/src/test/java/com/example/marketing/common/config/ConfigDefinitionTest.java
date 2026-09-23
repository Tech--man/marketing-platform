package com.example.marketing.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 边界校验。写侧拒才算反馈，读侧的"逐条忽略"只是兜底——
 * 越界值进了 DB 而运营看到"保存成功"，下次大促才会炸。
 */
class ConfigDefinitionTest {

    @Test
    @DisplayName("INT：区间内通过（含端点），越界/非数字/空值拒绝")
    void intBounds() {
        ConfigDefinition d = ConfigDefinition.ofInt("gateway.ratelimit.seckill-route.limit", 200, 1, 200000, "x");
        assertTrue(d.accepts("120"));
        assertTrue(d.accepts("1"));
        assertTrue(d.accepts("200000"));
        assertFalse(d.accepts("0"), "0 会让该路由全拒，不该在取值范围内");
        assertFalse(d.accepts("200001"));
        assertFalse(d.accepts("abc"));
        assertFalse(d.accepts("12.5"));
        assertFalse(d.accepts(null));
        assertFalse(d.accepts("  "));
    }

    @Test
    @DisplayName("LONG：接受大数，下界同样生效")
    void longBounds() {
        ConfigDefinition d = ConfigDefinition.ofLong("seckill.bought-mark-ttl-seconds", 86400, 60, 604800, "x");
        assertTrue(d.accepts("604800"));
        assertFalse(d.accepts("59"));
        assertEquals(86400L, d.longDefault());
    }

    @Test
    @DisplayName("STRING：只受长度约束，空串非法")
    void textMaxLength() {
        ConfigDefinition d = ConfigDefinition.ofText("demo.text", 8, "a", "x");
        assertTrue(d.accepts("hello"));
        assertFalse(d.accepts("123456789"));
        assertFalse(d.accepts(""));
    }

    @Test
    @DisplayName("边界写反在构造期就失败")
    void reversedBoundsFailFast() {
        assertThrows(IllegalArgumentException.class, () -> ConfigDefinition.ofInt("bad", 5, 10, 1, "x"));
    }
}
