package com.example.marketing.common.cache;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自检注册表：④ 读的是这里绑出去的 gauge，所以"取不到"与"是 0"必须在指标层就分开。
 */
class CacheConsistencyRegistryTest {

    private static CacheConsistency check(String type, int value) {
        return new CacheConsistency() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public int mismatchCount() {
                return value;
            }
        };
    }

    @Test
    @DisplayName("每个 type 一条带 tag 的 gauge，取的就是该模块自己的判定")
    void bindsOneGaugePerType() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CacheConsistencyRegistry registry = new CacheConsistencyRegistry(
                List.of(check("budget", 0), check("seckill-stock", 2)), meters);

        assertEquals(List.of("budget", "seckill-stock"), registry.types());
        assertEquals(0.0, gauge(meters, "budget").value());
        assertEquals(2.0, gauge(meters, "seckill-stock").value());
    }

    @Test
    @DisplayName("无法判定要能原样传出去：-1 不能被压成 0")
    void unknownIsNotZero() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        new CacheConsistencyRegistry(List.of(check("coupon-stock", -1)), meters);

        assertEquals(-1.0, gauge(meters, "coupon-stock").value());
        assertEquals(-1, new CacheConsistencyRegistry(List.of(check("budget", -1)), meters)
                .mismatchCount("budget"));
    }

    @Test
    @DisplayName("没注册过的 type 也是 -1，不是 0")
    void unregisteredTypeIsUnknown() {
        CacheConsistencyRegistry registry = new CacheConsistencyRegistry(List.of(), new SimpleMeterRegistry());

        assertEquals(-1, registry.mismatchCount("never-registered"));
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    @DisplayName("同 type 重复注册启动期失败，而不是后一份静默盖掉前一份")
    void duplicateTypeFails() {
        assertThrows(IllegalStateException.class, () -> new CacheConsistencyRegistry(
                List.of(check("budget", 0), check("budget", 1)), new SimpleMeterRegistry()));
    }

    private static Gauge gauge(SimpleMeterRegistry meters, String type) {
        Gauge gauge = meters.find(CacheConsistencyRegistry.METRIC).tag("type", type).gauge();
        assertNotNull(gauge, "type=" + type + " 的 gauge 没绑上");
        return gauge;
    }

    private static void assertNotNull(Object o, String msg) {
        org.junit.jupiter.api.Assertions.assertNotNull(o, msg);
    }
}
