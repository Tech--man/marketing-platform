package com.example.marketing.common.mq;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis Stream 实现（Lite 形态）：XADD 到 MKT_STREAM_{topic}，字段携带 tag/key/payload。
 *
 * <p>选型说明：单机小内存环境不想起 RocketMQ 集群（namesrv+broker 约 1.5G），
 * Redis Stream 的消费组（XGROUP/XREADGROUP/XACK）足以支撑演示级"至少一次 + 消费幂等"
 * 语义；生产环境切回 RocketMQ 只需改 marketing.mq.type。</p>
 */
public class RedisStreamEventPublisher implements EventPublisher {

    /** stream key 前缀：与限流/库存 key 同 namespace 隔离 */
    public static final String STREAM_KEY_PREFIX = "MKT_STREAM_";

    public static final String FIELD_TAG = "tag";
    public static final String FIELD_KEY = "key";
    public static final String FIELD_PAYLOAD = "payload";

    private final StringRedisTemplate redisTemplate;

    public RedisStreamEventPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public static String streamKey(String topic) {
        return STREAM_KEY_PREFIX + topic;
    }

    @Override
    public boolean publish(String topic, String tag, String bizKey, String payload) {
        Map<String, String> fields = new HashMap<>();
        fields.put(FIELD_TAG, tag);
        fields.put(FIELD_KEY, bizKey);
        fields.put(FIELD_PAYLOAD, payload);
        RecordId id = redisTemplate.opsForStream().add(MapRecord.create(streamKey(topic), fields));
        return id != null;
    }
}
