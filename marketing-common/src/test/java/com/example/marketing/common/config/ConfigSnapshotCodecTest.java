package com.example.marketing.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读侧"永不抛"是刻意的：坏载荷必须退化成空快照（= 退回出厂值），
 * 而不是把异常抛进轮询线程、让它抱着上一次的陈旧值继续跑。
 */
class ConfigSnapshotCodecTest {

    @Test
    @DisplayName("写出再读回，版本/值/类型不丢")
    void roundTrip() {
        ConfigSnapshot s = new ConfigSnapshot(7L, "2026-09-23T10:00:00Z", Map.of(
                "gateway.ratelimit.coupon-route.limit",
                new ConfigSnapshot.Entry("120", ConfigType.INT, 5L)));
        ConfigSnapshot back = ConfigSnapshotCodec.read(ConfigSnapshotCodec.write(s));
        assertEquals(7L, back.version());
        assertEquals("120", back.entries().get("gateway.ratelimit.coupon-route.limit").value());
        assertEquals(ConfigType.INT, back.entries().get("gateway.ratelimit.coupon-route.limit").type());
        assertEquals("2026-09-23T10:00:00Z", back.generatedAt());
    }

    @Test
    @DisplayName("null、空串、垃圾、字面量 null 都得到空快照")
    void garbageBecomesEmptySnapshot() {
        assertTrue(ConfigSnapshotCodec.read(null).entries().isEmpty());
        assertTrue(ConfigSnapshotCodec.read("{ not json").entries().isEmpty());
        assertTrue(ConfigSnapshotCodec.read("").entries().isEmpty());
        assertEquals(0L, ConfigSnapshotCodec.read("null").version());
    }

    @Test
    @DisplayName("载荷里出现读方不认识的字段时照旧解析（前向兼容）")
    void unknownFieldsIgnored() {
        String json = "{\"version\":3,\"generatedAt\":\"x\",\"extra\":1,"
                + "\"entries\":{\"a\":{\"value\":\"1\",\"type\":\"INT\",\"defVer\":0,\"future\":\"y\"}}}";
        assertEquals("1", ConfigSnapshotCodec.read(json).entries().get("a").value());
        assertEquals(3L, ConfigSnapshotCodec.read(json).version());
    }
}
