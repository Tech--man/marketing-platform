package com.example.marketing.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 阻塞侧的快照轮询器：每 {@code pollSeconds} 比对版本，变了才取全量快照。
 *
 * <p>用自起的 daemon 线程而不是 {@code @Scheduled}：六个进程里只有 standalone 标了
 * {@code @EnableScheduling}，靠它就得给每个模块各加一处开关，而漏加的表现是
 * "在线改完没反应"——那是最难查的一类问题。</p>
 *
 * <p>Redis 异常一律保住上一次生效值并记计数：一次抖动不该把限流阈值打回出厂值。
 * 这与"快照被删则退回出厂"是两条相反的路径，区分它们的是"Redis 说不存在"与"Redis 没说清"。</p>
 */
@Slf4j
public class ConfigSnapshotPoller {

    /** schema 不长变，但 Redis 被清空后必须自愈：每隔这么久重投一次自述 */
    private static final long SCHEMA_REPUBLISH_SECONDS = 60L;

    private final StringRedisTemplate redis;
    private final ConfigValues values;
    private final ConfigSchemaRegistry registry;
    private final String ownForm;
    private final String service;
    private final long pollSeconds;
    private final MeterRegistry meters;
    private final AtomicLong tick = new AtomicLong();
    private volatile ScheduledExecutorService scheduler;

    public ConfigSnapshotPoller(StringRedisTemplate redis, ConfigValues values,
                                ConfigSchemaRegistry registry, String ownForm,
                                String service, long pollSeconds, MeterRegistry meters) {
        this.redis = redis;
        this.values = values;
        this.registry = registry;
        this.ownForm = ConfigForm.resolve(ownForm);
        this.service = service;
        this.pollSeconds = Math.max(1L, pollSeconds);
        this.meters = meters;
        if (ConfigForm.unrecognized(ownForm)) {
            log.warn("[config] DEPLOY_FORM 取值 '{}' 无法识别，按 GLOBAL 解析（只认全局覆盖）", ownForm);
        } else if (ownForm == null || ownForm.trim().isEmpty()) {
            log.info("[config] 未设置 DEPLOY_FORM，在线配置只对 form=GLOBAL 的行生效");
        }
    }

    public void start() {
        // 启动先取一次：否则冷启动后第一个 5s 跑在出厂阈值上，改过的值"看起来没生效"
        refreshOnce();
        publishSchema();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mkt-config-poll");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::refreshOnce, pollSeconds, pollSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
    }

    /** 读版本 → 变了读快照 → 逐条校验后应用；任何异常都吃掉并保住现值 */
    void refreshOnce() {
        try {
            String raw = redis.opsForValue().get(ConfigKeys.version(ownForm));
            long current = raw == null ? 0L : Long.parseLong(raw.trim());
            if (current == values.appliedVersion() && current != 0L) {
                maybeRepublishSchema();
                return;
            }
            ConfigSnapshot snapshot = ConfigSnapshotCodec.read(
                    redis.opsForValue().get(ConfigKeys.snapshot(ownForm)));
            values.apply(snapshot);
            List<String> degraded = values.degradedKeys();
            if (!degraded.isEmpty()) {
                log.warn("[config] form={} 快照中 {} 个条目未被采纳（未声明或越界），已退回出厂值: {}",
                        ownForm, degraded.size(), degraded);
            }
            log.info("[config] form={} 生效快照 version={}, entries={}, degraded={}",
                    ownForm, snapshot.version(), snapshot.entries().size(), degraded.size());
            maybeRepublishSchema();
        } catch (Exception e) {
            meters.counter("marketing.config.poll.error").increment();
            log.warn("[config] 刷新失败，沿用上一次生效值 version={}: {}",
                    values.appliedVersion(), e.toString());
        }
    }

    /** 把本进程声明的可改参数自述写进 Redis，供后台渲染与校验；后台不 import 任何业务模块 */
    void publishSchema() {
        if (registry.all().isEmpty()) {
            // 没有可改参数的服务不写空自述：那会让 unreported 失去意义
            return;
        }
        try {
            Map<String, String> owners = registry.serviceByKey();
            List<Map<String, Object>> defs = new ArrayList<>();
            for (ConfigDefinition d : registry.all()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("key", d.key());
                // owner 是模块名：LITE 下进程统一是 standalone，但参数归属仍按模块显示
                one.put("owner", owners.getOrDefault(d.key(), service));
                one.put("type", d.type().name());
                one.put("min", d.min());
                one.put("max", d.max());
                one.put("defaultValue", d.defaultValue());
                one.put("description", d.description() == null ? "" : d.description());
                defs.add(one);
            }
            redis.opsForValue().set(ConfigKeys.schema(service), ConfigSchemaCodec.write(
                    new ConfigSchemaPayload(Instant.now().getEpochSecond(), service, defs)));
        } catch (Exception e) {
            log.warn("[config] schema 自述写入失败（不影响本进程取值）: {}", e.toString());
        }
    }

    private void maybeRepublishSchema() {
        long every = Math.max(1L, SCHEMA_REPUBLISH_SECONDS / pollSeconds);
        if (tick.incrementAndGet() % every == 0) {
            publishSchema();
        }
    }
}
