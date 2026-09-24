package com.example.marketing.admin.observe;

import com.example.marketing.common.mq.MqTopics;
import com.example.marketing.common.mq.RedisStreamEventPublisher;
import com.example.marketing.common.transport.StreamKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 队列深度：哪些"投出去了但还没被消费"的东西正在堆着。
 *
 * <p>三条不同的总线，形态各异地存在：</p>
 * <ul>
 *   <li>{@code MKT_STREAM_*} —— 只有 Redis Stream 通道（LITE/dev）有；RocketMQ 通道下
 *       broker 队列深度客户端读不到，这里<b>标 NOT_APPLICABLE 而不是填 0</b>
 *       （与 {@code load-probe.sh} 同口径，母版 §7）；</li>
 *   <li>{@code mkt:audit:pending} —— ③ 的审计总线，三种形态都在；</li>
 *   <li>{@code mkt:reheat:{type}:pending} —— ③ 的重预热总线，<b>按 type 分键</b>，
 *       type 来自注册表而不是猜。</li>
 * </ul>
 *
 * <p>键名一律来自 common 的常量：这里写错一个前缀的表现是"积压 0"，而不是任何报错。</p>
 *
 * <p>刻意<b>不</b>标 {@code @Component}：构造要的是"当前是不是 Redis Stream 通道"与
 * "本进程注册了哪些 reheat type"，两者都由 T6 的装配处显式喂进来；
 * 交给组件扫描只会在启动期报"无法注入 boolean/List"。</p>
 */
@Slf4j
public class StreamDepth {

    private static final long UNKNOWN = -1L;

    /**
     * @param len     XLEN，-1 表示读不到
     * @param pending 消费组 PEL 里的条数，-1 表示读不到（含组还不存在）
     * @param note    人读的口径说明（"本形态走 RocketMQ，broker 深度客户端读不到"）
     * @param error   非 null 即这一项没读到；<b>只影响这一行</b>，其余读数照常
     */
    public record Depth(String key, String group, long len, long pending,
                        boolean applicable, String note, String error) {
    }

    private final StringRedisTemplate redis;
    private final boolean redisStreamChannel;
    private final List<String> reheatTypes;

    public StreamDepth(StringRedisTemplate redis, boolean redisStreamChannel, List<String> reheatTypes) {
        this.redis = redis;
        this.redisStreamChannel = redisStreamChannel;
        this.reheatTypes = List.copyOf(reheatTypes);
    }

    public List<Depth> backlog() {
        List<Depth> out = new ArrayList<>();
        out.add(streamChannel("grant", MqTopics.TOPIC_COUPON_GRANT, MqTopics.GROUP_COUPON));
        out.add(streamChannel("order", MqTopics.TOPIC_SECKILL_ORDER, MqTopics.GROUP_SECKILL));
        out.add(probe(StreamKeys.auditPending(), StreamKeys.ADMIN_DRAIN_GROUP));
        for (String type : reheatTypes) {
            out.add(probe(StreamKeys.reheatPending(type), StreamKeys.OWNING_CONSUMER_GROUP));
        }
        return List.copyOf(out);
    }

    /** 业务消息通道：Redis Stream 时能读；RocketMQ 时诚实标不可见 */
    private Depth streamChannel(String label, String topic, String group) {
        String key = RedisStreamEventPublisher.streamKey(topic);
        if (!redisStreamChannel) {
            return new Depth(key, group, UNKNOWN, UNKNOWN, false,
                    "本形态走 RocketMQ，" + label + " 通道的队列深度在 broker 里，客户端读不到（要准数看 broker 控制台）",
                    null);
        }
        return probe(key, group);
    }

    private Depth probe(String key, String group) {
        long len = UNKNOWN;
        long pending = UNKNOWN;
        String error = null;
        try {
            Long xlen = redis.execute((RedisCallback<Long>) conn ->
                    conn.xLen(key.getBytes(StandardCharsets.UTF_8)));
            len = xlen == null ? 0L : xlen;
        } catch (RuntimeException e) {
            error = "XLEN " + reason(e);
        }
        try {
            var summary = redis.execute((RedisCallback<org.springframework.data.redis.connection.stream.PendingMessagesSummary>) conn ->
                    conn.xPending(key.getBytes(StandardCharsets.UTF_8), group));
            pending = summary == null ? 0L : summary.getTotalPendingMessages();
        } catch (RuntimeException e) {
            // 组还不存在（owning 服务没起来 / 还没建组）与 Redis 不通是两种不同的诊断，都写进 error
            error = error == null ? "XPENDING " + reason(e) : error + "; XPENDING " + reason(e);
        }
        return new Depth(key, group, len, pending, true,
                pending == UNKNOWN ? "PEL 读不到，可能消费组尚未建立" : "", error);
    }

    private static String reason(RuntimeException e) {
        String m = e.getMessage();
        if (m == null) {
            return e.getClass().getSimpleName();
        }
        int nl = m.indexOf('\n');
        return e.getClass().getSimpleName() + ": " + (nl < 0 ? m : m.substring(0, nl));
    }
}
