package com.example.marketing.common.cache;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 收集各模块的 {@link CacheConsistency}，按 type 绑成 gauge {@code marketing.cache.consistency{type}}。
 *
 * <p>写法照 {@link CacheReheatRegistry}：同一 type 重复注册在启动期就失败，而不是运行时后一个
 * 静默盖掉前一个——被盖掉的那份口径还在被写缓存的代码用，自检却在看另一份。</p>
 *
 * <p>gauge 的取值时刻是"有人来抓"的那一刻（④ 的读、或 Prometheus 的 scrape），
 * 不是常驻轮询：自检每条约两次读，常驻会把 LITE 那台弱主机的空闲 CPU 吃在这件事上。</p>
 */
@Slf4j
public class CacheConsistencyRegistry {

    public static final String METRIC = "marketing.cache.consistency";

    private final Map<String, CacheConsistency> byType = new LinkedHashMap<>();

    public CacheConsistencyRegistry(List<CacheConsistency> consistencyChecks, MeterRegistry meters) {
        consistencyChecks.forEach(check -> {
            CacheConsistency previous = byType.putIfAbsent(check.type(), check);
            if (previous != null) {
                throw new IllegalStateException("type=" + check.type() + " 的缓存自检被重复注册："
                        + previous.getClass().getName() + " 与 " + check.getClass().getName()
                        + "。两份口径早晚分叉，分叉的表现是自检说一切正常而账其实不对。");
            }
            meters.gauge(METRIC, java.util.List.of(io.micrometer.core.instrument.Tag.of("type", check.type())),
                    check, c -> (double) c.mismatchCount());
        });
        if (!byType.isEmpty()) {
            log.info("[cache] 已注册缓存一致性自检: {}", byType.keySet());
        }
    }

    public List<String> types() {
        return new ArrayList<>(byType.keySet());
    }

    /** 未注册的 type 返回 -1（无法判定），不是 0 */
    public int mismatchCount(String type) {
        CacheConsistency check = byType.get(type);
        return check == null ? -1 : check.mismatchCount();
    }

    /** 逐 type 的读数快照（-1 = 该模块判定不了） */
    public Map<String, Integer> snapshot() {
        Map<String, Integer> out = new LinkedHashMap<>();
        byType.forEach((type, check) -> out.put(type, check.mismatchCount()));
        return out;
    }
}
