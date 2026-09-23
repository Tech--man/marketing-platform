package com.example.marketing.common.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 本进程收集到的声明合集，按 key 索引。
 *
 * <p>同一 key 被两个 provider 声明会在构造期失败而不是后写覆盖：一个键两处边界
 * 就是两套真相，与 {@code CacheReheatRegistry} 的处置同源。</p>
 */
public class ConfigSchemaRegistry {

    private final Map<String, ConfigDefinition> byKey = new LinkedHashMap<>();
    private final Map<String, String> serviceByKey = new LinkedHashMap<>();

    public ConfigSchemaRegistry(List<ConfigDefinitionProvider> providers) {
        for (ConfigDefinitionProvider provider : providers) {
            for (ConfigDefinition definition : provider.definitions()) {
                ConfigDefinition previous = byKey.putIfAbsent(definition.key(), definition);
                if (previous != null) {
                    throw new IllegalStateException("配置键重复声明: " + definition.key()
                            + "（已由 " + serviceByKey.get(definition.key()) + " 声明）");
                }
                serviceByKey.put(definition.key(), provider.service());
            }
        }
    }

    /** 空注册表：给"只按出厂值跑"的测试与裸 {@link ConfigValues#empty()} 用 */
    public static ConfigSchemaRegistry empty() {
        return new ConfigSchemaRegistry(List.of());
    }

    public Optional<ConfigDefinition> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public boolean declares(String key) {
        return byKey.containsKey(key);
    }

    public List<ConfigDefinition> all() {
        return List.copyOf(byKey.values());
    }

    /** key → 声明它的模块名（④ 展示归属、⑥ 分组渲染都取这个） */
    public Map<String, String> serviceByKey() {
        return Map.copyOf(serviceByKey);
    }
}
