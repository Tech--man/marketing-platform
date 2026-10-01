package com.example.marketing.common.mq;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N-7：LITE 通道 tag 拒投分支的契约。此前该分支是死代码（acceptsTag 默认 true 且
 * 全仓无覆写），拒投既无计数也无测试——本用例直接驱动 package-private 的 deliver，
 * 不起 worker 线程（afterPropertiesSet 会开线程轮询，不适合单测）。
 */
class StreamConsumerRegistrarTest {

    private static final String KEY = "MKT_STREAM_T";

    @SuppressWarnings("unchecked")
    private static StreamOperations<String, Object, Object> mockStreamOps(StringRedisTemplate redis) {
        StreamOperations<String, Object, Object> ops = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(ops);
        return ops;
    }

    private static MapRecord<String, Object, Object> record(String tag, String id) {
        return StreamRecords.newRecord()
                .in(KEY)
                .withId(RecordId.of(id))
                .ofMap(tag == null
                        ? Map.of(RedisStreamEventPublisher.FIELD_PAYLOAD, "{}")
                        : Map.of(RedisStreamEventPublisher.FIELD_TAG, tag,
                                 RedisStreamEventPublisher.FIELD_PAYLOAD, "{}"));
    }

    /** handler 桩：accepted=false 模拟"消息 tag 不属于本 handler"（N-7 的分叉场景） */
    private static final StreamMessageHandler PICKY = new StreamMessageHandler() {
        @Override public String topic() { return "T"; }
        @Override public String group() { return "G"; }
        @Override public void handle(String payload) {
            throw new IllegalStateException("拒投的消息绝不能进 handle");
        }
        @Override public boolean acceptsTag(String tag) { return "MINE".equals(tag); }
    };

    @Test
    @DisplayName("tag 不属于本 handler：拒投 + XACK/XDEL + stream.consumer.tag_rejected 计数")
    void foreignTagIsRejectedAcksDeletesAndCounted() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> ops = mockStreamOps(redis);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        StreamConsumerRegistrar registrar =
                new StreamConsumerRegistrar(redis, List.of(PICKY), 1, meters);

        registrar.deliver(PICKY, KEY, record("OTHER", "1-1"));

        verify(ops).acknowledge(KEY, "G", RecordId.of("1-1"));
        verify(ops).delete(KEY, RecordId.of("1-1"));
        assertEquals(1.0, meters.counter("stream.consumer.tag_rejected").count(),
                "拒投必须计数（alerts.yml 的 StreamTagRejected 靠它）");
    }

    @Test
    @DisplayName("属于自己的 tag / 无 tag 的旧消息：照常投递，拒投计数不动")
    void ownTagAndTaglessLegacyMessageStillDeliver() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> ops = mockStreamOps(redis);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        StreamMessageHandler handler = new StreamMessageHandler() {
            @Override public String topic() { return "T"; }
            @Override public String group() { return "G"; }
            @Override public void handle(String payload) { /* 成功 */ }
            @Override public boolean acceptsTag(String tag) { return "MINE".equals(tag); }
        };
        StreamConsumerRegistrar registrar =
                new StreamConsumerRegistrar(redis, List.of(handler), 1, meters);

        registrar.deliver(handler, KEY, record("MINE", "1-1"));
        // 无 tag 的消息（迁移前 local_message 行 tag 列为空）：跳过校验照常投递
        registrar.deliver(handler, KEY, record(null, "2-1"));

        verify(ops).acknowledge(KEY, "G", RecordId.of("1-1"));
        verify(ops).acknowledge(KEY, "G", RecordId.of("2-1"));
        assertEquals(0.0, meters.counter("stream.consumer.tag_rejected").count());
    }
}
