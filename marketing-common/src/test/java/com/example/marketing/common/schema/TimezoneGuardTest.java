package com.example.marketing.common.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时区守卫（P2-2）：只告警不拦启动，可测的事实是"期望值与 JVM 实际是否一致"
 * 的判定本身——判错方向（永远 mismatch / 永远 OK）都会被这里抓住。
 */
class TimezoneGuardTest {

    @Test
    @DisplayName("期望=当前 JVM 时区 → 判定一致，启动路径不告警")
    void matchingZoneNotMismatched() {
        TimezoneGuard guard = new TimezoneGuard(ZoneId.systemDefault().getId());
        assertEquals(false, guard.mismatch());
        assertDoesNotThrow(guard::afterSingletonsInstantiated);
    }

    @Test
    @DisplayName("期望与 JVM 不一致 → mismatch=true（告警路径），且不抛异常")
    void mismatchedZoneWarnsWithoutThrowing() {
        // 找一个必然不同于当前的合法时区：当前是 +08 就挑 UTC，反之挑 Asia/Shanghai
        String current = ZoneId.systemDefault().getId();
        String other = current.equals("Asia/Shanghai") ? "UTC" : "Asia/Shanghai";
        assertNotEquals(current, other);
        TimezoneGuard guard = new TimezoneGuard(other);
        assertTrue(guard.mismatch(), "期望 " + other + " 而当前 " + current + " 必须判为不齐");
        assertDoesNotThrow(guard::afterSingletonsInstantiated, "时区不齐只告警不拦启动");
    }
}
