package com.example.marketing.common.reheat;

import com.example.marketing.common.cache.CacheReheatRegistry;
import com.example.marketing.common.cache.CacheReheater;
import com.example.marketing.common.transport.StreamKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 执行侧的三条约束：只接自己的 type、回执必须落、接不住也要回执。
 *
 * <p>投递端的 {@code DISPATCHED} 只是"我交出去了"，能把它变成"真的刷过了"的唯一证据
 * 就是这个类写的回执。所以<b>任何</b>退出路径都要留下回执或计数：没有 FAILED 回执时，
 * 后台只能把"没回音"猜成"还在排队"，而运营据此做的下一个动作是再点一次 ——
 * 那正是本项目最贵的静默不一致。</p>
 */
@SuppressWarnings("unchecked")
class ReheatDispatcherTest {

    private static final String TYPE = "budget";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final StreamOperations<String, Object, Object> stream = mock(StreamOperations.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    /** 记录被调用了几次的假 reheater */
    private int calls;
    private boolean boom;

    private final CacheReheater reheater = new CacheReheater() {
        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public Result reheat(String key, boolean force) {
            calls++;
            if (boom) {
                throw new IllegalStateException("redis down");
            }
            return new Result(TYPE, key, 100L, 7000L, "formula");
        }
    };

    private ReheatDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(redis.opsForStream()).thenReturn(stream);
        when(redis.opsForValue()).thenReturn(values);
        dispatcher = new ReheatDispatcher(redis, new CacheReheatRegistry(List.of(reheater)), meters, 5);
    }

    private MapRecord<String, Object, Object> record(ReheatPayloads.Request request) {
        return MapRecord.<String, Object, Object>create(StreamKeys.reheatPending(request.type()),
                        Map.<Object, Object>of(ReheatCodec.FIELD, ReheatCodec.writeRequest(request)))
                .withId(RecordId.of("1-0"));
    }

    private ReheatPayloads.Request request() {
        return new ReheatPayloads.Request("7", TYPE, "ACT2026001", true, "admin", 1_790_000_000L);
    }

    private void stubRead(List<MapRecord<String, Object, Object>> batch) {
        when(stream.read(any(Consumer.class), any(StreamReadOptions.class),
                any(StreamOffset.class))).thenReturn(batch);
    }

    @Test
    @DisplayName("执行完写回执、再确认并删除条目")
    void executesAndWritesReceipt() {
        stubRead(List.of(record(request())));

        dispatcher.drainAll();

        assertEquals(1, calls);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(StreamKeys.reheatAck(TYPE, "7")), json.capture(),
                eq(StreamKeys.REHEAT_RECEIPT_TTL));
        ReheatPayloads.Ack ack = ReheatCodec.readAck(json.getValue()).orElseThrow();
        assertEquals("DONE", ack.status());
        assertEquals(7000L, ack.after(), "回执要带回执行后的值，运营据此核对口径");
        assertEquals("ACT2026001", ack.key());
        verify(stream).acknowledge(eq(StreamKeys.reheatPending(TYPE)),
                eq(StreamKeys.OWNING_CONSUMER_GROUP), any(RecordId.class));
        verify(stream).delete(eq(StreamKeys.reheatPending(TYPE)), any(RecordId.class));
    }

    @Test
    @DisplayName("执行抛了也写 FAILED 回执：没有它后台只能把无回音猜成排队中")
    void failureStillProducesReceipt() {
        boom = true;
        stubRead(List.of(record(request())));

        dispatcher.drainAll();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(StreamKeys.reheatAck(TYPE, "7")), json.capture(),
                eq(StreamKeys.REHEAT_RECEIPT_TTL));
        ReheatPayloads.Ack ack = ReheatCodec.readAck(json.getValue()).orElseThrow();
        assertEquals("FAILED", ack.status());
        assertTrue(ack.error().contains("redis down"));
        assertEquals(1.0, meters.counter("marketing.reheat.exec.failed", "type", TYPE).count());
        // 失败也要确认并删除：否则下一条同 id 的重试会被 PEL 里这条卡住
        verify(stream).acknowledge(eq(StreamKeys.reheatPending(TYPE)),
                eq(StreamKeys.OWNING_CONSUMER_GROUP), any(RecordId.class));
    }

    @Test
    @DisplayName("脏载荷跳过并计数，但仍然确认：一条坏数据不能把这条流卡死")
    void skipsUndecodableAndStillAcks() {
        MapRecord<String, Object, Object> dirty = MapRecord.<String, Object, Object>create(
                        StreamKeys.reheatPending(TYPE), Map.<Object, Object>of(ReheatCodec.FIELD, "{oops"))
                .withId(RecordId.of("2-0"));
        stubRead(List.of(dirty, record(request())));

        dispatcher.drainAll();

        assertEquals(1.0, meters.counter("marketing.reheat.dispatch.skipped").count());
        verify(values, times(1)).set(anyString(), anyString(), any(Duration.class));
        verify(values).set(eq(StreamKeys.reheatAck(TYPE, "7")), anyString(),
                eq(StreamKeys.REHEAT_RECEIPT_TTL));
        verify(stream, times(2)).acknowledge(eq(StreamKeys.reheatPending(TYPE)),
                eq(StreamKeys.OWNING_CONSUMER_GROUP), any(RecordId.class));
    }

    @Test
    @DisplayName("只为本注册表里的 type 建组：没有该类型的进程去读，等于抢走别人的请求再判失败")
    void subscribesOnlyItsOwnTypes() {
        dispatcher.start();
        try {
            verify(stream).createGroup(eq(StreamKeys.reheatPending(TYPE)), any(ReadOffset.class),
                    eq(StreamKeys.OWNING_CONSUMER_GROUP));
            verify(stream, never()).createGroup(eq(StreamKeys.reheatPending("coupon")),
                    any(ReadOffset.class), anyString());
        } finally {
            dispatcher.stop();
        }
    }

    @Test
    @DisplayName("建组从 0 开始：默认最新位置会让'先投递后启动'的那次重预热永远不被读")
    void groupStartsFromZero() {
        dispatcher.ensureGroup(TYPE);

        ArgumentCaptor<ReadOffset> offset = ArgumentCaptor.forClass(ReadOffset.class);
        verify(stream).createGroup(eq(StreamKeys.reheatPending(TYPE)), offset.capture(),
                eq(StreamKeys.OWNING_CONSUMER_GROUP));
        assertEquals("0", offset.getValue().getOffset());
    }

    @Test
    @DisplayName("本进程一个 reheater 都没有时不启动轮询（admin / 网关不该白读 Redis）")
    void emptyRegistryDoesNotStartPolling() {
        ReheatDispatcher idle = new ReheatDispatcher(redis,
                new CacheReheatRegistry(List.of()), meters, 5);
        idle.start();
        try {
            verify(stream, never()).createGroup(anyString(), any(ReadOffset.class), anyString());
        } finally {
            idle.stop();
        }
    }

    @Test
    @DisplayName("Redis 本轮读取失败只计数不抛：调度线程死了等于整条总线停了")
    void redisFailureIsSwallowed() {
        when(stream.read(any(Consumer.class), any(StreamReadOptions.class),
                any(StreamOffset.class))).thenThrow(new IllegalStateException("redis down"));

        dispatcher.drainAll();

        assertEquals(1.0, meters.counter("marketing.reheat.dispatch.error").count());
        assertEquals(0, calls);
    }

    @Test
    @DisplayName("建组撞 BUSYGROUP（组已存在）是正常情况，不报警也不影响后续")
    void busyGroupIsFine() {
        when(stream.createGroup(anyString(), any(ReadOffset.class), anyString()))
                .thenThrow(new RuntimeException("BUSYGROUP Consumer Group name already exists"));

        dispatcher.ensureGroup(TYPE);

        verify(stream).createGroup(eq(StreamKeys.reheatPending(TYPE)), any(ReadOffset.class),
                eq(StreamKeys.OWNING_CONSUMER_GROUP));
    }
}
