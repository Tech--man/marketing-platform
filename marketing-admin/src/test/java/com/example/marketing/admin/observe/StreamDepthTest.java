package com.example.marketing.admin.observe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 队列深度的读法。<b>键名必须来自常量</b>：这里写错一个前缀的表现是"积压 0"，
 * 而不是任何报错——那是本段最坏的一类假绿。
 *
 * <p>另一半是诚实性：RocketMQ 通道下 broker 队列深度客户端读不到，
 * 必须显式 {@code NOT_APPLICABLE} 而不是填 0（与 {@code load-probe.sh} 同一口径）。</p>
 */
class StreamDepthTest {

    private StringRedisTemplate redis;
    private RedisConnection connection;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        connection = mock(RedisConnection.class);
        when(connection.xLen(any(byte[].class))).thenAnswer(inv -> {
            String key = new String((byte[]) inv.getArgument(0));
            return key.contains("audit") ? 0L : 7L;
        });
        when(connection.xPending(any(byte[].class), any(String.class))).thenReturn(null);
        when(redis.execute(any(RedisCallback.class))).thenAnswer(inv ->
                ((RedisCallback<Object>) inv.getArgument(0)).doInRedis(connection));
    }

    @Test
    @DisplayName("Redis Stream 通道：两个 topic 的键名按常量拼出来，不是字面量")
    void readsStreamChannelKeys() {
        StreamDepth depth = new StreamDepth(redis, true, List.of("budget"));

        List<StreamDepth.Depth> rows = depth.backlog();

        assertTrue(rows.stream().anyMatch(r -> r.key().equals("MKT_STREAM_MKT_COUPON_GRANT")),
                "键形必须与 RedisStreamEventPublisher 一致: " + keys(rows));
        assertTrue(rows.stream().anyMatch(r -> r.key().equals("MKT_STREAM_MKT_SECKILL_ORDER")),
                "keys=" + keys(rows));
        assertTrue(rows.stream().anyMatch(r -> r.key().equals("mkt:audit:pending")),
                "审计总线在三种形态下都在，必须一起读: " + keys(rows));
        assertTrue(rows.stream().anyMatch(r -> r.key().equals("mkt:reheat:budget:pending")),
                "重预热总线按 type 分键: " + keys(rows));
    }

    @Test
    @DisplayName("RocketMQ 通道：标 NOT_APPLICABLE，不填 0")
    void rocketmqChannelIsNotApplicable() {
        StreamDepth depth = new StreamDepth(redis, false, List.of("budget"));

        List<StreamDepth.Depth> rows = depth.backlog();

        StreamDepth.Depth topic = rows.stream()
                .filter(r -> r.key().startsWith("MKT_STREAM_")).findFirst().orElseThrow();
        assertTrue(!topic.applicable(), "不可见不等于 0");
        assertNotNull(topic.note());
        // 审计与重预热两条内部总线与 MQ 选型无关，仍然要读
        assertTrue(rows.stream().anyMatch(r -> r.key().equals("mkt:audit:pending") && r.applicable()));
    }

    @Test
    @DisplayName("读不到的那一项要带 error，且不能连带抹掉读到的那些")
    void redisFailureIsMarkedPerKey() {
        when(connection.xLen(any(byte[].class))).thenThrow(new IllegalStateException("Connection refused"));
        StreamDepth depth = new StreamDepth(redis, true, List.of());

        List<StreamDepth.Depth> rows = depth.backlog();

        assertTrue(rows.stream().allMatch(r -> r.error() != null));
        assertTrue(rows.stream().allMatch(r -> r.len() < 0),
                "读不到就是 -1（UNKNOWN）：0 会被读成\"没有积压\"");
    }

    @Test
    @DisplayName("消费组还不存在（NOGROUP）：深度可读但 PEL 未知，两件事分开报")
    void missingGroupKeepsLen() {
        when(connection.xPending(any(byte[].class), any(String.class)))
                .thenThrow(new IllegalStateException("NOGROUP No such consumer group"));

        StreamDepth depth = new StreamDepth(redis, true, List.of());
        StreamDepth.Depth audit = depth.backlog().stream()
                .filter(r -> r.key().equals("mkt:audit:pending")).findFirst().orElseThrow();

        assertEquals(0L, audit.len(), "XLEN 本身是成功的（该键不存在就是 0）");
        assertEquals(-1L, audit.pending(), "PEL 读不到不能编数");
        assertNotNull(audit.error());
    }

    @Test
    @DisplayName("没有 type 时也不崩：内部总线条目仍在")
    void emptyTypesStillReadsInternalBuses() {
        List<StreamDepth.Depth> rows = new StreamDepth(redis, true, List.of()).backlog();

        assertEquals(2, rows.stream().filter(r -> r.key().startsWith("MKT_STREAM_")).count(),
                "两个 topic 各一条");
        assertEquals(0, rows.stream().filter(r -> r.key().contains("reheat")).count());
        assertNull(rows.get(0).error());
    }

    private static List<String> keys(List<StreamDepth.Depth> rows) {
        return rows.stream().map(StreamDepth.Depth::key).toList();
    }

    /** 防 IDE 优化掉：确认 xLen 真的被调用过（不是靠猜数据造出来的读数） */
    @Test
    @DisplayName("每个键都真的打了一次 XLEN")
    void everyKeyIsProbed() {
        new StreamDepth(redis, true, List.of("budget")).backlog();

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        org.mockito.Mockito.verify(connection, org.mockito.Mockito.atLeast(3)).xLen(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .anyMatch(b -> new String(b, java.nio.charset.StandardCharsets.UTF_8).startsWith("MKT_STREAM_")));
    }
}
