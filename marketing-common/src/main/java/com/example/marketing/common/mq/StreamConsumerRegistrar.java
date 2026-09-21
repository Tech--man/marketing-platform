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
 * Redis Stream 消费容器（Lite 形态）：为每个 {@link StreamMessageHandler} 起一条
 * 守护线程轮询 XREADGROUP，处理成功 XACK；失败重试 3 次后放弃本条投递。
 *
 * <p>可靠性说明：放弃的消息一定未被本地消息表 confirm（confirm 在 handler 成功后
 * 才执行），{@code LocalMessageRetryer} 会按退避重新 publish 一条新消息，
 * "至少一次 + 消费幂等"闭环不依赖 Stream 的 PEL 重投。</p>
 */
@Slf4j
public class StreamConsumerRegistrar implements InitializingBean, DisposableBean {

    /** 单轮拉取条数 */
    private static final int READ_COUNT = 16;
    /** 处理失败重试次数 */
    private static final int HANDLE_RETRY = 3;
    /** 空轮询间隔（毫秒） */
    private static final long POLL_IDLE_MS = 200;

    private final StringRedisTemplate redisTemplate;
    private final List<StreamMessageHandler> handlers;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;

    public StreamConsumerRegistrar(StringRedisTemplate redisTemplate, List<StreamMessageHandler> handlers) {
        this.redisTemplate = redisTemplate;
        this.handlers = handlers;
    }

    @Override
    public void afterPropertiesSet() {
        running = true;
        for (StreamMessageHandler handler : handlers) {
            startWorker(handler);
        }
        log.info("[stream-consumer] Redis Stream 消费容器启动，订阅 topic 数: {}", handlers.size());
    }

    @Override
    public void destroy() {
        running = false;
        workers.forEach(Thread::interrupt);
    }

    private void startWorker(StreamMessageHandler handler) {
        String key = RedisStreamEventPublisher.streamKey(handler.topic());
        ensureGroup(key, handler.group());
        org.springframework.data.redis.connection.stream.Consumer consumer =
                org.springframework.data.redis.connection.stream.Consumer
                        .from(handler.group(), handler.group() + "-c1");
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
                    sleepQuietly(POLL_IDLE_MS);
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    deliver(handler, key, record);
                }
            }
        }, "stream-consumer-" + handler.topic());
        worker.setDaemon(true);
        worker.start();
        workers.add(worker);
    }

    private void deliver(StreamMessageHandler handler, String key, MapRecord<String, Object, Object> record) {
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
        try {
            redisTemplate.opsForStream().acknowledge(key, handler.group(), record.getId());
        } catch (Exception e) {
            log.warn("[stream-consumer] XACK 失败（不影响正确性）: {}", e.getMessage());
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
