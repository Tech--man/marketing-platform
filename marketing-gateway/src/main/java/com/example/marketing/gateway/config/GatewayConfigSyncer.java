package com.example.marketing.gateway.config;

import com.example.marketing.common.config.ConfigDefinition;
import com.example.marketing.common.config.ConfigForm;
import com.example.marketing.common.config.ConfigKeys;
import com.example.marketing.common.config.ConfigSchemaCodec;
import com.example.marketing.common.config.ConfigSchemaPayload;
import com.example.marketing.common.config.ConfigSchemaRegistry;
import com.example.marketing.common.config.ConfigSnapshotCodec;
import com.example.marketing.common.config.ConfigSyncer;
import com.example.marketing.common.config.ConfigValues;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网关侧的配置同步器：与 common 的 {@code ConfigSnapshotPoller} 同一件事，但只用
 * {@code ReactiveRedisTemplate}——网关没有 DataSource，也不该为在线配置引入阻塞调用链
 * （母版事实 #8：那等于把一个 JDBC 式线程模型塞进事件循环进程）。
 *
 * <p>实现 {@link ConfigSyncer} 是为了让 common 的阻塞轮询器在本进程让位：网关的 classpath 上
 * 其实存在 {@code StringRedisTemplate} bean（reactive starter 会带进 spring-data-redis 核心），
 * 少了这个标记就会出现"两套节拍同时喂同一份生效值"。</p>
 *
 * <p>读的是常量键、写的是自己的自述，用户可控输入为零。</p>
 */
@Slf4j
@Component
public class GatewayConfigSyncer implements ConfigSyncer {

    private static final long SCHEMA_REPUBLISH_SECONDS = 60L;

    private final ReactiveRedisTemplate<String, String> redis;
    private final ConfigValues values;
    private final ConfigSchemaRegistry registry;
    private final String ownForm;
    private final String service;
    private final long pollSeconds;
    private final MeterRegistry meters;
    private final AtomicLong tick = new AtomicLong();

    public GatewayConfigSyncer(ReactiveRedisTemplate<String, String> redis, ConfigValues values,
                               ConfigSchemaRegistry registry,
                               @Value("${marketing.config.form:${DEPLOY_FORM:}}") String form,
                               @Value("${spring.application.name:marketing-gateway}") String service,
                               @Value("${marketing.config.poll-seconds:${CONFIG_POLL_SECONDS:5}}") long pollSeconds,
                               MeterRegistry meters) {
        this.redis = redis;
        this.values = values;
        this.registry = registry;
        this.ownForm = ConfigForm.resolve(form);
        this.service = service;
        this.pollSeconds = Math.max(1L, pollSeconds);
        this.meters = meters;
        if (ConfigForm.unrecognized(form)) {
            log.warn("[config] 网关 DEPLOY_FORM='{}' 无法识别，按 GLOBAL 解析", form);
        }
    }

    @PostConstruct
    public void start() {
        syncOnce().subscribe();
        publishSchema().subscribe();
        Flux.interval(Duration.ofSeconds(pollSeconds), Duration.ofSeconds(pollSeconds))
                .subscribe(n -> {
                    syncOnce().subscribe();
                    if (tick.incrementAndGet() % Math.max(1L, SCHEMA_REPUBLISH_SECONDS / pollSeconds) == 0) {
                        publishSchema().subscribe();
                    }
                }, e -> log.error("[config] 网关轮询链异常终止（在线配置将停止更新）", e));
    }

    /** 版本比对 → 变了才取全量快照；异常吃掉并保住现值 */
    Mono<Void> syncOnce() {
        long expected = values.appliedVersion();
        return redis.opsForValue().get(ConfigKeys.version(ownForm))
                .defaultIfEmpty("")
                .map(raw -> raw.trim().isEmpty() ? 0L : Long.parseLong(raw.trim()))
                .flatMap(current -> {
                    if (current == expected && current != 0L) {
                        return Mono.<String>empty();
                    }
                    return redis.opsForValue().get(ConfigKeys.snapshot(ownForm)).defaultIfEmpty("");
                })
                .doOnNext(this::apply)
                .doOnError(e -> {
                    meters.counter("marketing.config.poll.error").increment();
                    log.warn("[config] 网关刷新失败，沿用 version={}: {}",
                            values.appliedVersion(), e.toString());
                })
                .onErrorComplete()
                .then();
    }

    /** 自述可改参数：后台只读这些键渲染表单与校验，不 import 网关任何类 */
    Mono<Void> publishSchema() {
        if (registry.all().isEmpty()) {
            return Mono.empty();
        }
        Map<String, String> owners = registry.serviceByKey();
        List<Map<String, Object>> defs = new ArrayList<>();
        for (ConfigDefinition d : registry.all()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("key", d.key());
            one.put("owner", owners.getOrDefault(d.key(), service));
            one.put("type", d.type().name());
            one.put("min", d.min());
            one.put("max", d.max());
            one.put("defaultValue", d.defaultValue());
            one.put("description", d.description() == null ? "" : d.description());
            defs.add(one);
        }
        return redis.opsForValue().set(ConfigKeys.schema(service), ConfigSchemaCodec.write(
                        new ConfigSchemaPayload(Instant.now().getEpochSecond(), service, defs)))
                .doOnError(e -> log.warn("[config] 网关 schema 自述写入失败: {}", e.toString()))
                .onErrorComplete()
                .then();
    }

    private void apply(String json) {
        values.apply(ConfigSnapshotCodec.read(json));
        List<String> degraded = values.degradedKeys();
        if (!degraded.isEmpty()) {
            log.warn("[config] 网关 form={} 有 {} 个条目未采纳，退回 yml 出厂值: {}",
                    ownForm, degraded.size(), degraded);
        }
        log.info("[config] 网关 form={} version={} entries={} degraded={}",
                ownForm, values.appliedVersion(), ConfigSnapshotCodec.read(json).entries().size(),
                degraded.size());
    }
}
