package com.example.marketing.common.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 读取侧的生效值容器：整个进程唯一一份，volatile 换指针，读路径零锁。
 *
 * <p>校验只在 {@link #apply} 做一次（每 5s 一次），不在取值时做——秒杀与领券是热路径，
 * 每次抢购都 parse 一遍数字是不能接受的。</p>
 *
 * <p>"缺值时退回哪"由调用方决定：它把 yml/代码出厂值作为 fallback 传进来，
 * 于是限流阈值天然就是"既不过限、也绝不让网关起不来"的回退语义。</p>
 */
public class ConfigValues {

    private final ConfigSchemaRegistry registry;
    private final MeterRegistry meters;
    private final AtomicReference<Map<String, String>> effective = new AtomicReference<>(Map.of());
    private final AtomicReference<ConfigSnapshot> snapshot = new AtomicReference<>(ConfigSnapshot.empty());
    private final AtomicReference<List<String>> degraded = new AtomicReference<>(List.of());

    public ConfigValues(ConfigSchemaRegistry registry, MeterRegistry meters) {
        this.registry = registry;
        this.meters = meters;
        meters.gauge("marketing.config.snapshot.version", snapshot, s -> (double) s.get().version());
        meters.gauge("marketing.config.degraded.size", degraded, d -> (double) d.get().size());
    }

    /** 无在线覆盖的裸容器：给单测与"只按出厂值跑"的路径用 */
    public static ConfigValues empty() {
        return new ConfigValues(ConfigSchemaRegistry.empty(), new SimpleMeterRegistry());
    }

    public void apply(ConfigSnapshot next) {
        Map<String, String> accepted = new LinkedHashMap<>();
        List<String> ignored = new ArrayList<>();
        for (Map.Entry<String, ConfigSnapshot.Entry> entry : next.entries().entrySet()) {
            Optional<ConfigDefinition> def = registry.find(entry.getKey());
            if (def.isEmpty() || !def.get().accepts(entry.getValue().value())) {
                ignored.add(entry.getKey());
                Counter.builder("marketing.config.entry.ignored")
                        .tag("key", entry.getKey())
                        .register(meters)
                        .increment();
                continue;
            }
            accepted.put(entry.getKey(), entry.getValue().value().trim());
        }
        effective.set(Map.copyOf(accepted));
        degraded.set(List.copyOf(ignored));
        snapshot.set(next);
    }

    public int intOr(String key, int fallback) {
        Long v = coerce(key, ConfigType.INT);
        return v == null ? fallback : v.intValue();
    }

    public long longOr(String key, long fallback) {
        Long v = coerce(key, ConfigType.LONG);
        return v == null ? fallback : v;
    }

    public String stringOr(String key, String fallback) {
        Optional<ConfigDefinition> def = registry.find(key);
        if (def.isEmpty() || def.get().type() != ConfigType.STRING) {
            return fallback;
        }
        String raw = effective.get().get(key);
        return raw == null ? fallback : raw;
    }

    /** 当前这个键的值确实来自在线快照（而不是 fallback） */
    public boolean overridden(String key) {
        return effective.get().containsKey(key);
    }

    public long appliedVersion() {
        return snapshot.get().version();
    }

    /** 最近一次 apply 里被忽略的键：④ 的 degraded 展示与冒烟断言都读它 */
    public List<String> degradedKeys() {
        return degraded.get();
    }

    private Long coerce(String key, ConfigType expect) {
        Optional<ConfigDefinition> def = registry.find(key);
        if (def.isEmpty() || def.get().type() != expect) {
            return null;
        }
        // effective 里的值已在 apply 时校验过，这里只数值化，不重复边界判断
        return def.get().coerce(effective.get().get(key));
    }
}
