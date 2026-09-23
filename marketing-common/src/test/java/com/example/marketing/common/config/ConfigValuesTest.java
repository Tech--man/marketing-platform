package com.example.marketing.common.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读取侧生效值：逐条按**本地声明**校验，未声明与非法都忽略并退回调用方给的出厂值。
 *
 * <p>"未声明就忽略"是"不一致时代码赢"的那一半：DB 里残留一行陈旧配置（比如参数已从代码里删掉）
 * 绝不能凭空生效，也不能凭空把服务搞挂。</p>
 */
class ConfigValuesTest {

    private static final ConfigDefinition LIMIT =
            ConfigDefinition.ofInt("gateway.ratelimit.coupon-route.limit", 1000, 1, 200000, "券领取阈值");
    private static final ConfigDefinition TTL =
            ConfigDefinition.ofLong("seckill.token-ttl-seconds", 600, 30, 86400, "排队 token 时长");

    private static ConfigValues values() {
        ConfigSchemaRegistry registry = new ConfigSchemaRegistry(List.of(new ConfigDefinitionProvider() {
            @Override
            public String service() {
                return "unit-test";
            }

            @Override
            public List<ConfigDefinition> definitions() {
                return List.of(LIMIT, TTL);
            }
        }));
        return new ConfigValues(registry, new SimpleMeterRegistry());
    }

    private static ConfigSnapshot one(String key, String value) {
        return new ConfigSnapshot(1L, "now",
                Map.of(key, new ConfigSnapshot.Entry(value, ConfigType.INT, 0L)));
    }

    @Test
    @DisplayName("快照里有合法值时用快照值")
    void appliesValidSnapshotValue() {
        ConfigValues v = values();
        v.apply(one(LIMIT.key(), "120"));
        assertEquals(120, v.intOr(LIMIT.key(), LIMIT.intDefault()));
        assertTrue(v.overridden(LIMIT.key()));
        assertEquals(1L, v.appliedVersion());
    }

    @Test
    @DisplayName("越界值逐条忽略、退回出厂值并记进 degraded；坏邻居不影响同批的好条目")
    void outOfRangeIgnoredPerEntry() {
        ConfigValues v = values();
        v.apply(new ConfigSnapshot(2L, "now", Map.of(
                LIMIT.key(), new ConfigSnapshot.Entry("0", ConfigType.INT, 0L),
                TTL.key(), new ConfigSnapshot.Entry("60", ConfigType.INT, 0L))));
        assertEquals(1000, v.intOr(LIMIT.key(), 1000));
        assertEquals(60L, v.longOr(TTL.key(), 600L));
        assertEquals(List.of(LIMIT.key()), v.degradedKeys());
    }

    @Test
    @DisplayName("别的进程声明的键：取值走不到它，也不算本进程降级（共享快照里这是常态）")
    void foreignKeyIsIgnoredSilently() {
        ConfigValues v = values();
        // discount.nope 由 discount 进程声明，券/网关进程读快照时都不认识它
        v.apply(one("discount.nope", "5"));
        assertEquals(9, v.intOr("discount.nope", 9));
        assertTrue(v.degradedKeys().isEmpty(),
                "把别人的键算成降级会让每个健康进程每 5 秒刷一条 WARN，真降级就埋了（FULL 实测）");
    }

    @Test
    @DisplayName("类型不符的键仍然算降级（不能把'我该采纳却采纳不了'也吞掉）")
    void declaredButWrongTypeIsDegraded() {
        ConfigValues v = values();
        // TTL 声明是 LONG，快照里给了个非数字
        v.apply(new ConfigSnapshot(1L, "now",
                Map.of(TTL.key(), new ConfigSnapshot.Entry("abc", ConfigType.LONG, 0L))));
        assertEquals(List.of(TTL.key()), v.degradedKeys());
        assertEquals(600L, v.longOr(TTL.key(), 600L));
    }

    @Test
    @DisplayName("空快照 → 全部退回出厂值，overridden 为 false")
    void emptySnapshotMeansFactoryDefaults() {
        ConfigValues v = values();
        v.apply(one(LIMIT.key(), "120"));
        v.apply(ConfigSnapshot.empty());
        assertEquals(1000, v.intOr(LIMIT.key(), 1000));
        assertFalse(v.overridden(LIMIT.key()));
        assertEquals(0L, v.appliedVersion());
        assertTrue(v.degradedKeys().isEmpty());
    }

    @Test
    @DisplayName("类型不符的声明走 fallback（INT 键不会从 longOr 拿到值）")
    void typeMismatchFallsBack() {
        ConfigValues v = values();
        v.apply(one(LIMIT.key(), "120"));
        assertEquals(7L, v.longOr(LIMIT.key(), 7L), "LIMIT 声明的是 INT，longOr 不该用它");
    }

    @Test
    @DisplayName("STRING 键取字符串值，越界（超长）同样逐条忽略")
    void stringKeysHonourMaxLength() {
        ConfigSchemaRegistry registry = new ConfigSchemaRegistry(List.of(new ConfigDefinitionProvider() {
            @Override
            public String service() {
                return "unit-test";
            }

            @Override
            public List<ConfigDefinition> definitions() {
                return List.of(ConfigDefinition.ofText("demo.text", 4, "ab", "x"));
            }
        }));
        ConfigValues v = new ConfigValues(registry, new SimpleMeterRegistry());
        v.apply(one("demo.text", "abcd"));
        assertEquals("abcd", v.stringOr("demo.text", "fallback"));
        v.apply(one("demo.text", "toolongvalue"));
        assertEquals("fallback", v.stringOr("demo.text", "fallback"));
    }
}
