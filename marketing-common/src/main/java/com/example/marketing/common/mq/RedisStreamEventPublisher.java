package com.example.marketing.common.mq;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis Stream 实现（Lite 形态）：XADD 到 MKT_STREAM_{topic}，字段携带 tag/key/payload。
 *
 * <p>选型说明：单机小内存环境不想起 RocketMQ 集群（namesrv+broker 约 1.5G），
 * Redis Stream 的消费组（XGROUP/XREADGROUP/XACK）足以支撑 LITE 服役档的
 * "至少一次 + 消费幂等"语义；活跃期切回 RocketMQ 只需改 marketing.mq.type。</p>
 */
@Slf4j
public class RedisStreamEventPublisher implements EventPublisher {

    /** stream key 前缀：与限流/库存 key 同 namespace 隔离 */
    public static final String STREAM_KEY_PREFIX = "MKT_STREAM_";

    /** 修剪闸门：流内条目数上限（近似） */
    private static final long STREAM_MAX_LEN = 100_000L;

    /** 每多少次投递做一次 XTRIM，避免给热路径加一倍往返 */
    private static final int TRIM_EVERY_N_PUBLISH = 1000;

    public static final String FIELD_TAG = "tag";
    public static final String FIELD_KEY = "key";
    public static final String FIELD_PAYLOAD = "payload";

    private final StringRedisTemplate redisTemplate;

    /** 上次修剪以来的投递条数，用于把 XTRIM 的开销摊薄到可忽略 */
    private final AtomicInteger sinceLastTrim = new AtomicInteger();

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
        if (id != null) {
            trimIfNeeded(topic);
        }
        return id != null;
    }

    /**
     * 失控闸门。日常释放靠消费端的 XDEL（见 {@link StreamConsumerRegistrar}），但那条路径
     * 只在"有人在消费"时成立；消费者整体停摆时 Stream 仍会无界增长直到把 Redis 撑满。
     * 故在发布侧按条数节流做一次近似修剪。
     *
     * <p>被修剪掉的未消费条目由本地消息表补偿重投（{@code LocalMessageRetryer}），
     * 代价是最多一个退避周期的延迟，不是丢消息——所以这道闸门可以放心设小。</p>
     *
     * <p>计数器跨 topic 共用：修剪只作用于触发这次投递的 topic，最坏情况是某个 topic 多涨
     * 若干个投递批次才被自己触发的那次修剪收住，相对闸门量级可忽略。</p>
     */
    private void trimIfNeeded(String topic) {
        if (sinceLastTrim.decrementAndGet() > 0) {
            return;
        }
        sinceLastTrim.set(TRIM_EVERY_N_PUBLISH);
        String key = streamKey(topic);
        try {
            redisTemplate.opsForStream().trim(key, STREAM_MAX_LEN, true);
        } catch (Exception e) {
            log.warn("[stream] XTRIM 失败（不影响投递）key={}: {}", key, e.getMessage());
        }
    }
}
