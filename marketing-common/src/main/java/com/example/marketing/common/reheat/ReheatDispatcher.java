package com.example.marketing.common.reheat;

import com.example.marketing.common.cache.CacheReheatRegistry;
import com.example.marketing.common.cache.CacheReheater;
import com.example.marketing.common.transport.StreamKeys;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * owning 服务这一侧的重预热执行器：读自己那 type 的 pending 流 → 执行 → 写回执。
 *
 * <p>为什么需要它：FULL 分进程下 admin 进程里没有任何 {@link CacheReheater}，
 * ①② 只能回它 {@code 41010 本形态不适用}。③ 的口径是"显式报错可以，但不能静默成功"，
 * 而更好的做法是把动作交给真正有能力做的进程做、并把结果带回来（母版 §6.3）。</p>
 *
 * <p>只为本注册表里存在的 type 建消费组：没有该类型的进程去读，等于把别人的请求抢走再判失败。</p>
 */
@Slf4j
public class ReheatDispatcher {

    private static final int BATCH = 50;

    private final StringRedisTemplate redis;
    private final CacheReheatRegistry registry;
    private final MeterRegistry meters;
    private final long pollSeconds;
    /** 同 type 多副本时靠消费组分摊：一条重预热只会被一个副本执行 */
    private final String consumerName = "exec-" + UUID.randomUUID().toString().substring(0, 8);

    private volatile ScheduledExecutorService scheduler;

    public ReheatDispatcher(StringRedisTemplate redis, CacheReheatRegistry registry,
                            MeterRegistry meters, long pollSeconds) {
        this.redis = redis;
        this.registry = registry;
        this.meters = meters;
        this.pollSeconds = Math.max(1L, pollSeconds);
    }

    /** 由 {@code @Bean(initMethod = "start")} 调，与 ⑤ 的轮询器同一写法（两个入口会启动两轮线程） */
    public void start() {
        List<String> types = registry.types();
        if (types.isEmpty()) {
            // admin / 网关这类进程没有 reheater，起了轮询只会白读 Redis。
            // 显式不启动，而不是"启动了但什么都不做"。
            log.debug("[reheat] 本进程无 CacheReheater，不启动执行器");
            return;
        }
        types.forEach(this::ensureGroup);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mkt-reheat-exec");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::drainAll, 0L, pollSeconds, TimeUnit.SECONDS);
        log.info("[reheat] 本进程承接的重预热类型: {}", types);
    }

    public void stop() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
    }

    void ensureGroup(String type) {
        try {
            // 从 0 建组：默认的最新位置会让"先投递、后启动 owning 服务"的那次重预热
            // 永远不被读——而它恰恰是最常见的情形（服务滚动重启时运营刚点了刷新）。
            // 反过来不会重放：组已存在时这里撞 BUSYGROUP 被忽略，而 admin 只在有组时才投递，
            // 所以能被"从 0"读到的只有建组瞬间之前那批无人认领的条目。
            redis.opsForStream().createGroup(StreamKeys.reheatPending(type),
                    ReadOffset.from("0"), StreamKeys.OWNING_CONSUMER_GROUP);
        } catch (RuntimeException e) {
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")) {
                log.warn("[reheat] 建消费组失败 type={}: {}", type, e.toString());
            }
        }
    }

    /** 每轮把自己那几个 type 各取一批；一个 type 出错不影响其他 type */
    void drainAll() {
        for (String type : registry.types()) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(StreamKeys.OWNING_CONSUMER_GROUP, consumerName),
                        StreamReadOptions.empty().count(BATCH),
                        StreamOffset.create(StreamKeys.reheatPending(type), ReadOffset.lastConsumed()));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                records.forEach(record -> executeOne(type, record));
            } catch (RuntimeException e) {
                meters.counter("marketing.reheat.dispatch.error").increment();
                log.warn("[reheat] type={} 本轮读取失败: {}", type, e.toString());
            }
        }
    }

    private void executeOne(String type, MapRecord<String, Object, Object> record) {
        Object raw = record.getValue() == null ? null : record.getValue().get(ReheatCodec.FIELD);
        Optional<ReheatPayloads.Request> parsed = ReheatCodec.readRequest(raw == null ? null : String.valueOf(raw));
        if (parsed.isEmpty()) {
            meters.counter("marketing.reheat.dispatch.skipped").increment();
            log.warn("[reheat] 载荷无法解析，跳过并确认 type={}, id={}", type, record.getId());
        } else {
            ReheatPayloads.Request request = parsed.get();
            ReheatPayloads.Ack ack = run(type, request);
            redis.opsForValue().set(StreamKeys.reheatAck(type, request.id()),
                    ReheatCodec.writeAck(ack), StreamKeys.REHEAT_RECEIPT_TTL);
            meters.counter("marketing.reheat.executed", "type", type).increment();
        }
        // 与审计总线同一顺序：先 ACK 再 XDEL。反过来会在"已删未确认"的窗口里
        // 让这条请求既没执行也没痕迹
        redis.opsForStream().acknowledge(StreamKeys.reheatPending(type),
                StreamKeys.OWNING_CONSUMER_GROUP, record.getId());
        redis.opsForStream().delete(StreamKeys.reheatPending(type), record.getId());
    }

    /** 执行失败也要有回执：没有 FAILED 回执，后台就只能把"没回音"猜成"还在排队" */
    private ReheatPayloads.Ack run(String type, ReheatPayloads.Request request) {
        try {
            CacheReheater.Result result = registry.reheat(type, request.key(), request.force());
            log.info("[reheat] 已执行 type={}, key={}, before={}, after={}, force={}, actor={}",
                    result.type(), result.key(), result.before(), result.after(),
                    request.force(), request.actor());
            return ReheatPayloads.Ack.done(request, result.before(), result.after());
        } catch (RuntimeException e) {
            meters.counter("marketing.reheat.exec.failed", "type", type).increment();
            log.warn("[reheat] 执行失败 type={}, key={}: {}", type, request.key(), e.toString());
            return ReheatPayloads.Ack.failed(request, e.toString());
        }
    }
}
