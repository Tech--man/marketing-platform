package com.example.marketing.common.audit;

import com.example.marketing.common.transport.StreamKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 投递侧的四条：键与字段名固定、带上界、绝不设 TTL、Redis 故障不外溢。
 *
 * <p>"绝不设 TTL"看起来反直觉（消息堆着不管？），但审计丢一条的代价高于 Redis 长一点，
 * 而 {@code XLEN} 在 ④ 是可见指标 —— 这是 ③ 选 Stream 而不是 {@code LPUSH+LTRIM+TTL} 的全部理由，
 * 所以它需要一条断言钉住，而不是靠注释。</p>
 */
@SuppressWarnings("unchecked")
class AuditOutboxTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final StreamOperations<String, Object, Object> stream = mock(StreamOperations.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private AuditOutbox outbox;

    @BeforeEach
    void setUp() {
        when(redis.opsForStream()).thenReturn(stream);
        outbox = new AuditOutbox(redis, meters);
    }

    private AuditPayload payload() {
        return new AuditPayload(1L, "admin", "admin", "activity.budget.set", "activity",
                "ACT2026001", "PUT", "/api/admin/activities/ACT2026001/budget",
                "from=70.00, to=80.00", 0, "", "127.0.0.1", 5L, 1_800_000_000L);
    }

    @Test
    @DisplayName("投递即 XADD 到 mkt:audit:pending，且只有一个 payload 字段")
    void addsSinglePayloadField() {
        outbox.record(payload());
        verify(stream).add(eq(StreamKeys.auditPending()),
                argThat((Map<Object, Object> m) -> m.size() == 1
                        && "payload".equals(m.keySet().iterator().next())
                        && String.valueOf(m.values().iterator().next()).contains("activity.budget.set")));
    }

    @Test
    @DisplayName("每次投递都带上界 XTRIM：admin 长时间不消费不能把 Redis 撑大")
    void capsStreamLength() {
        outbox.record(payload());
        verify(stream).trim(StreamKeys.auditPending(), StreamKeys.MAX_LEN);
    }

    @Test
    @DisplayName("绝不设 TTL：TTL 淘汰等于静默丢审计")
    void neverSetsExpire() {
        outbox.record(payload());
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Redis 抛异常时只 warn + 计数：审计是副作用，不能把业务写带下水")
    void redisFailureDoesNotPropagate() {
        when(stream.add(anyString(), anyMap())).thenThrow(new IllegalStateException("redis down"));
        assertDoesNotThrowRecord();
        assertEquals(1.0, meters.counter("marketing.audit.outbox.error").count());
    }

    private void assertDoesNotThrowRecord() {
        try {
            outbox.record(payload());
        } catch (RuntimeException e) {
            throw new AssertionError("record 不得把 Redis 故障外溢给业务写路径", e);
        }
    }
}
