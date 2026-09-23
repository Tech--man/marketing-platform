package com.example.marketing.seckill.config;

import com.example.marketing.common.config.ConfigSchemaRegistry;
import com.example.marketing.common.config.ConfigSnapshot;
import com.example.marketing.common.config.ConfigType;
import com.example.marketing.common.config.ConfigValues;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三个 TTL 的取值优先级。这层包装存在的唯一理由就是让"漏改一处调用点"变成不可能：
 * 字段类型换掉之后，任何还直接读 {@code SeckillProperties} 的地方都编不过。
 */
class SeckillRuntimeConfigTest {

    private static ConfigValues withOnline(String key, String value) {
        ConfigValues values = new ConfigValues(new ConfigSchemaRegistry(
                List.of(new SeckillConfigDefinitions())), new SimpleMeterRegistry());
        values.apply(new ConfigSnapshot(1L, "now",
                Map.of(key, new ConfigSnapshot.Entry(value, ConfigType.LONG, 0L))));
        return values;
    }

    @Test
    @DisplayName("没有在线覆盖时用 SeckillProperties 的值（=今天的默认行为）")
    void fallsBackToProperties() {
        SeckillRuntimeConfig cfg = new SeckillRuntimeConfig(ConfigValues.empty(), new SeckillProperties());
        assertEquals(600L, cfg.tokenTtlSeconds());
        assertEquals(300L, cfg.payTimeoutSeconds());
        assertEquals(86400L, cfg.boughtMarkTtlSeconds());
    }

    @Test
    @DisplayName("在线值合法时优先；越界的条目退回出厂值而不是把 token 变成 5 秒")
    void onlineValueWinsAndOutOfRangeFallsBack() {
        assertEquals(120L, new SeckillRuntimeConfig(
                withOnline("seckill.token-ttl-seconds", "120"), new SeckillProperties()).tokenTtlSeconds());
        assertEquals(600L, new SeckillRuntimeConfig(
                withOnline("seckill.token-ttl-seconds", "5"), new SeckillProperties()).tokenTtlSeconds(),
                "5 低于下界 30，必须退回 600");
    }

    @Test
    @DisplayName("分桶数不进白名单：它同时是 seckill_activity.buckets 列，两套值会互相打脸")
    void bucketsNotOnlineEditable() {
        SeckillConfigDefinitions defs = new SeckillConfigDefinitions();
        assertEquals("marketing-seckill", defs.service());
        assertTrue(defs.definitions().stream().noneMatch(d -> d.key().contains("buckets")));
        assertEquals(3, defs.definitions().size());
        for (var d : defs.definitions()) {
            assertTrue(d.accepts(d.defaultValue()), d.key() + " 出厂值不自洽");
        }
    }
}
