package com.example.marketing.common.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 轮询器的四条路径，每条都钉住一个"会静默出事"的分支：
 * 版本没变不该取快照、变了必须取并应用、Redis 抛异常必须保住现值
 * （一次网络抖动不能把阈值打回出厂）、快照键被删必须退回出厂。
 */
class ConfigSnapshotPollerTest {

    private static final String KEY = "gateway.ratelimit.coupon-route.limit";

    private final Map<String, String> store = new ConcurrentHashMap<>();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ConfigValues values;
    private ConfigSnapshotPoller poller;
    private volatile boolean failNext;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> {
            if (failNext) {
                throw new IllegalStateException("redis down");
            }
            return store.get(inv.<String>getArgument(0));
        });
        // ValueOperations.set(K,V) 返回 void，只能用 doAnswer 桩，不能用 when()
        org.mockito.Mockito.doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString());
        ConfigSchemaRegistry registry = new ConfigSchemaRegistry(List.of(new ConfigDefinitionProvider() {
            @Override
            public String service() {
                return "marketing-gateway";
            }

            @Override
            public List<ConfigDefinition> definitions() {
                return List.of(ConfigDefinition.ofInt(KEY, 1000, 1, 200000, "券阈值"));
            }
        }));
        values = new ConfigValues(registry, new SimpleMeterRegistry());
        poller = new ConfigSnapshotPoller(redis, values, registry, "LITE", "marketing-gateway", 5,
                new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        poller.stop();
    }

    private void putSnapshot(long version, String value) {
        store.put(ConfigKeys.version("LITE"), String.valueOf(version));
        store.put(ConfigKeys.snapshot("LITE"), ConfigSnapshotCodec.write(new ConfigSnapshot(
                version, "now", Map.of(KEY, new ConfigSnapshot.Entry(value, ConfigType.INT, 1L)))));
    }

    @Test
    @DisplayName("版本变了才取快照并应用；同版本再刷不重复取全量")
    void fetchesSnapshotOnlyWhenVersionChanged() {
        putSnapshot(3L, "120");
        poller.refreshOnce();
        assertEquals(120, values.intOr(KEY, 1000));
        assertEquals(3L, values.appliedVersion());
        verify(ops, times(1)).get(ConfigKeys.snapshot("LITE"));

        poller.refreshOnce();
        verify(ops, times(1)).get(ConfigKeys.snapshot("LITE"));
    }

    @Test
    @DisplayName("快照键被删 → 退回出厂值（不报错、不保持旧值）")
    void deletedSnapshotFallsBackToFactory() {
        putSnapshot(3L, "120");
        poller.refreshOnce();
        store.remove(ConfigKeys.version("LITE"));
        store.remove(ConfigKeys.snapshot("LITE"));
        poller.refreshOnce();
        assertEquals(1000, values.intOr(KEY, 1000));
        assertEquals(0L, values.appliedVersion());
    }

    @Test
    @DisplayName("Redis 抛异常时保住上一次生效值，不把阈值打回出厂")
    void redisFailureKeepsLastApplied() {
        putSnapshot(3L, "120");
        poller.refreshOnce();
        failNext = true;
        poller.refreshOnce();
        assertEquals(120, values.intOr(KEY, 1000));
        assertEquals(3L, values.appliedVersion());
    }

    @Test
    @DisplayName("schema 自述写进本进程自己的键，内容含键、边界与 owner 模块名")
    void publishesOwnSchema() {
        poller.publishSchema();
        String json = store.get(ConfigKeys.schema("marketing-gateway"));
        assertNotNull(json, "自述必须落在 mkt:cfg:schema:{进程名}");
        assertTrue(json.contains(KEY), "自述里要能看到自己声明的键: " + json);
        assertTrue(json.contains("200000"), "自述里要带上边界，否则 admin 无法校验: " + json);
        assertTrue(json.contains("\"owner\":\"marketing-gateway\""), "自述要带模块名: " + json);
    }
}
