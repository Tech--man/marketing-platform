package com.example.marketing.common.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis Stream 消费容器（Lite 形态）：为每个 {@link StreamMessageHandler} 起
 * {@code concurrency} 条守护线程轮询 XREADGROUP（同组内 consumer 名唯一，Redis 原生
 * 保证一条消息只投给组内一个 consumer），处理成功 XACK + XDEL；失败重试 3 次后放弃本条投递。
 *
 * <p>可靠性说明：放弃的消息一定未被本地消息表 confirm（confirm 在 handler 成功后
 * 才执行），{@code LocalMessageRetryer} 会按退避重新 publish 一条新消息，
 * "至少一次 + 消费幂等"闭环不依赖 Stream 的 PEL 重投。</p>
 *
 * <p>并行度默认 8 是为了与 Full 形态对齐：同一段落库逻辑在 Full 由
 * {@code @RocketMQMessageListener(consumeThreadNumber = 8)} 驱动，若 LITE 只跑单线程，
 * 两形态就不只是容量差异，而是行为不等价。</p>
 */
@Slf4j
public class StreamConsumerRegistrar implements InitializingBean, DisposableBean {

    /** 单轮拉取条数 */
    private static final int READ_COUNT = 16;
    /** 处理失败重试次数 */
    private static final int HANDLE_RETRY = 3;
    /** 空轮询间隔（毫秒） */
    private static final long POLL_IDLE_MS = 200;
    /** PEL 回收门槛：空闲超过它的条目才认领（正常处理毫秒级，30s 只会是搁浅） */
    private static final long RECLAIM_MIN_IDLE_MS = 30_000L;

    private final StringRedisTemplate redisTemplate;
    private final List<StreamMessageHandler> handlers;
    private final int concurrency;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;

    public StreamConsumerRegistrar(StringRedisTemplate redisTemplate, List<StreamMessageHandler> handlers,
                                   int concurrency, io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.handlers = handlers;
        this.concurrency = Math.max(1, concurrency);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void afterPropertiesSet() {
        running = true;
        for (StreamMessageHandler handler : handlers) {
            for (int i = 1; i <= concurrency; i++) {
                startWorker(handler, i);
            }
        }
        log.info("[stream-consumer] Redis Stream 消费容器启动，订阅 topic 数: {}, 每 topic 并发: {}",
                handlers.size(), concurrency);
    }

    @Override
    public void destroy() {
        running = false;
        workers.forEach(Thread::interrupt);
    }

    private void startWorker(StreamMessageHandler handler, int index) {
        String key = RedisStreamEventPublisher.streamKey(handler.topic());
        ensureGroup(key, handler.group());
        org.springframework.data.redis.connection.stream.Consumer consumer =
                org.springframework.data.redis.connection.stream.Consumer
                        .from(handler.group(), handler.group() + "-c" + index);
        StreamOffset<String> offset = StreamOffset.create(key, ReadOffset.lastConsumed());

        Thread worker = new Thread(() -> {
            while (running) {
                List<MapRecord<String, Object, Object>> records;
                try {
                    records = redisTemplate.opsForStream()
                            .read(consumer, StreamReadOptions.empty().count(READ_COUNT), offset);
                } catch (Exception e) {
                    if (!running) {
                        return;
                    }
                    log.warn("[stream-consumer] XREADGROUP 异常 topic={}: {}", handler.topic(), e.getMessage());
                    sleepQuietly(1000);
                    continue;
                }
                if (records == null || records.isEmpty()) {
                    // W3.10：PEL 回收只由 index==0 的 worker 跑——每 topic 8 个 worker
                    // 空闲时各自打一轮 XPENDING 是全空闲下 ~80 次/s 的 Redis 空转
                    if (index == 0) {
                        reclaimStale(handler, key, consumer);
                    }
                    sleepQuietly(POLL_IDLE_MS);
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    deliver(handler, key, record);
                }
            }
        }, "stream-consumer-" + handler.topic() + "-" + index);
        worker.setDaemon(true);
        worker.start();
        workers.add(worker);
    }

    /**
     * PEL 回收（2026-09-29 审查）：认领"读走后长时间未确认"的条目。这条链路的
     * 正确性闭环在本地消息表（未 confirm 会重发），PEL 滞留的主要是"已处理、
     * XACK/XDEL 没打成"或"实例崩溃前刚读走"的条目——认领重投由消费端幂等兜住，
     * 回收的收益是 PEL 不再永久膨胀、XDEL（消费完删条目）对滞留条目也生效。
     * 只在空闲轮做，避免与正常读互相放大。
     */
    private void reclaimStale(StreamMessageHandler handler, String key,
                              org.springframework.data.redis.connection.stream.Consumer consumer) {
        try {
            org.springframework.data.redis.connection.stream.PendingMessages pending =
                    redisTemplate.opsForStream().pending(key, handler.group(),
                            org.springframework.data.domain.Range.unbounded(), READ_COUNT);
            if (pending == null || !pending.iterator().hasNext()) {
                return;
            }
            List<org.springframework.data.redis.connection.stream.RecordId> stale = new java.util.ArrayList<>();
            for (org.springframework.data.redis.connection.stream.PendingMessage message : pending) {
                if (message.getElapsedTimeSinceLastDelivery().toMillis() >= RECLAIM_MIN_IDLE_MS) {
                    stale.add(message.getId());
                }
            }
            if (stale.isEmpty()) {
                return;
            }
            List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream().claim(
                    key, handler.group(), consumer.getName(),
                    java.time.Duration.ofMillis(RECLAIM_MIN_IDLE_MS),
                    stale.toArray(org.springframework.data.redis.connection.stream.RecordId[]::new));
            if (claimed == null || claimed.isEmpty()) {
                return;
            }
            log.info("[stream-consumer] 认领 {} 条滞留 PEL 的消息 topic={}（XACK 中断或死实例搁浅）",
                    claimed.size(), handler.topic());
            claimed.forEach(record -> deliver(handler, key, record));
        } catch (Exception e) {
            log.warn("[stream-consumer] PEL 回收失败 topic={}: {}", handler.topic(), e.getMessage());
        }
    }

    // 可见性为 package-private：拒投分支（N-7）需要单测直接驱动 deliver，
    // 而启动 worker 线程的 afterPropertiesSet 不适合进单测。
    void deliver(StreamMessageHandler handler, String key, MapRecord<String, Object, Object> record) {
        // W3.10（2026-09-30）引入 tag 校验时只 WARN 仍无差别投递；2026-10-01 审计（架构层
        // "双形态分叉"项）升级为拒投：Redis Stream 通道不消费 tag（FULL 形态 RocketMQ 按
        // selectorExpression 路由），将来有人在已有 topic 上加第二个 tag 时，LITE 若照旧把
        // 消息塞给原 handler，等于按错误的类型处理载荷（错账方向不可控）——宁可拒投并留
        // ERROR 线索，让分叉在第一次发生时就被看见。拒投的消息仍走下方 XACK/XDEL：
        // 留在 PEL 只会被回收循环无限重投成毒消息。N-7 补齐配套：handler 侧 acceptsTag
        // 已有真实实现（各消费者返回自己的 selectorExpression 常量），拒投计数
        // stream.consumer.tag_rejected 进 Prometheus 告警——这段不再是死代码。
        Object tag = record.getValue().get(RedisStreamEventPublisher.FIELD_TAG);
        if (tag != null && !String.valueOf(tag).isBlank()
                && !handler.acceptsTag(String.valueOf(tag))) {
            // v3 复审 P3：带 topic 维度——告警响了要知道是哪个消费者的布局分叉
            io.micrometer.core.instrument.Counter.builder("stream.consumer.tag_rejected")
                    .tag("topic", handler.topic())
                    .description("LITE Stream 通道拒投的不属于本 handler 的 tag 消息数（两形态 tag 布局分叉信号）")
                    .register(meterRegistry).increment();
            log.error("[stream-consumer] 拒投：消息 tag={} 不属于本 handler（topic={}）。"
                            + "Stream 通道不做 tag 路由，FULL 形态会按 tag 分给别的消费者——"
                            + "两形态行为已分叉，请核对 topic/tag 布局；本条按错投丢弃（XACK）",
                    tag, handler.topic());
            ackAndDelete(handler, key, record);
            return;
        }
        Object payload = record.getValue().get(RedisStreamEventPublisher.FIELD_PAYLOAD);
        boolean ok = false;
        for (int attempt = 1; attempt <= HANDLE_RETRY && !ok; attempt++) {
            try {
                handler.handle(String.valueOf(payload));
                ok = true;
            } catch (Exception e) {
                log.warn("[stream-consumer] 处理失败({}/{}) topic={}: {}",
                        attempt, HANDLE_RETRY, handler.topic(), e.getMessage());
                sleepQuietly(1000);
            }
        }
        if (!ok) {
            // 放弃本条：未 confirm 的本地消息会被补偿定时器重发新消息
            log.error("[stream-consumer] 消息连续失败放弃，等待本地消息表补偿重发 topic={}", handler.topic());
        }
        ackAndDelete(handler, key, record);
    }

    private void ackAndDelete(StreamMessageHandler handler, String key,
                              MapRecord<String, Object, Object> record) {
        try {
            redisTemplate.opsForStream().acknowledge(key, handler.group(), record.getId());
            // XACK 只把条目从消费组 PEL 摘除，条目本身永久留在流里——不删就是常态服役下的内存泄漏。
            // 本形态每个 topic 只有一个消费组，删除安全；若将来引入第二个消费组（旁路审计/回放），
            // 必须改掉这里，否则新组读不到已被删的条目。
            redisTemplate.opsForStream().delete(key, record.getId());
        } catch (Exception e) {
            log.warn("[stream-consumer] XACK/XDEL 失败（不影响正确性）: {}", e.getMessage());
        }
    }

    /** 消费组初始化：makeStream=true 允许流不存在时建组；BUSYGROUP（组已存在）静默忽略 */
    private void ensureGroup(String key, String group) {
        try {
            redisTemplate.execute((RedisConnection connection) ->
                    connection.streamCommands().xGroupCreate(
                            key.getBytes(StandardCharsets.UTF_8), group, ReadOffset.from("0"), true));
        } catch (Exception e) {
            log.debug("[stream-consumer] 消费组已存在: {}", group);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
