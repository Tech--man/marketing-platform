package com.example.marketing.gateway.config;

import com.example.marketing.common.config.ConfigKeys;
import com.example.marketing.common.config.ConfigSchemaRegistry;
import com.example.marketing.common.config.ConfigSnapshot;
import com.example.marketing.common.config.ConfigSnapshotCodec;
import com.example.marketing.common.config.ConfigType;
import com.example.marketing.common.config.ConfigValues;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网关的读取侧：没有 DataSource、也只有 reactive Redis 客户端，所以这个同步器
 * 是网关唯一允许的取快照路径。
 */
class GatewayConfigSyncerTest {

    private static final String KEY = "gateway.ratelimit.seckill-route.limit";

    private static ConfigSchemaRegistry registry() {
        return new ConfigSchemaRegistry(List.of(new GatewayConfigDefinitions()));
    }

    private static String snapshotJson(long version, String value) {
        return ConfigSnapshotCodec.write(new ConfigSnapshot(version, "now",
                Map.of(KEY, new ConfigSnapshot.Entry(value, ConfigType.INT, 0L))));
    }

    @SuppressWarnings("unchecked")
    private ReactiveRedisTemplate<String, String> redis(String version, Mono<String> snapshot) {
        ReactiveRedisTemplate<String, String> redis = mock(ReactiveRedisTemplate.class);
        ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(eq(ConfigKeys.version("LITE")))).thenReturn(version == null ? Mono.empty() : Mono.just(version));
        when(ops.get(eq(ConfigKeys.snapshot("LITE")))).thenReturn(snapshot);
        when(ops.set(anyString(), anyString())).thenReturn(Mono.just(true));
        return redis;
    }

    private GatewayConfigSyncer syncer(ReactiveRedisTemplate<String, String> redis, ConfigValues values) {
        return new GatewayConfigSyncer(redis, values, registry(), "LITE", "marketing-gateway", 5,
                new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("按本进程形态取快照键，解析后喂进 ConfigValues")
    void syncsOwnFormSnapshot() {
        ConfigValues values = new ConfigValues(registry(), new SimpleMeterRegistry());
        syncer(redis("9", Mono.just(snapshotJson(9L, "120"))), values).syncOnce().block(Duration.ofSeconds(5));
        assertEquals(120, values.intOr(KEY, 200));
        assertEquals(9L, values.appliedVersion());
    }

    @Test
    @DisplayName("Redis 报错时保持现值（一次抖动不能把阈值打回出厂）")
    void redisErrorKeepsLastApplied() {
        ConfigValues values = new ConfigValues(registry(), new SimpleMeterRegistry());
        syncer(redis("9", Mono.just(snapshotJson(9L, "120"))), values).syncOnce().block(Duration.ofSeconds(5));
        syncer(redis(null, Mono.error(new IllegalStateException("boom"))), values).syncOnce()
                .block(Duration.ofSeconds(5));
        assertEquals(120, values.intOr(KEY, 200), "第二次同步失败后仍要用第一次的值");
    }

    @Test
    @DisplayName("版本与快照键都不在 → 退回 yml 出厂值（FALLBACK_YML 语义）")
    void missingKeysClearOverrides() {
        ConfigValues values = new ConfigValues(registry(), new SimpleMeterRegistry());
        syncer(redis("9", Mono.just(snapshotJson(9L, "120"))), values).syncOnce().block(Duration.ofSeconds(5));
        assertEquals(120, values.intOr(KEY, 200));
        syncer(redis(null, Mono.empty()), values).syncOnce().block(Duration.ofSeconds(5));
        assertEquals(200, values.intOr(KEY, 200));
        assertEquals(0L, values.appliedVersion());
    }

    @Test
    @DisplayName("自述写进本进程的 schema 键，内容含键与边界")
    void publishesSchema() {
        ConfigValues values = new ConfigValues(registry(), new SimpleMeterRegistry());
        ReactiveRedisTemplate<String, String> redis = redis("9", Mono.just(snapshotJson(9L, "120")));
        syncer(redis, values).publishSchema().block(Duration.ofSeconds(5));
        verify(redis.opsForValue()).set(eq(ConfigKeys.schema("marketing-gateway")),
                contains("gateway.ratelimit.seckill-route.limit"));
        verify(redis.opsForValue()).set(eq(ConfigKeys.schema("marketing-gateway")), contains("200000"));
        assertTrue(values.degradedKeys().isEmpty());
    }
}
