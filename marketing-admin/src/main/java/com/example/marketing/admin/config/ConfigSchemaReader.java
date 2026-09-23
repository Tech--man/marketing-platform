package com.example.marketing.admin.config;

import com.example.marketing.common.config.ConfigDefinition;
import com.example.marketing.common.config.ConfigKeys;
import com.example.marketing.common.config.ConfigSchemaCodec;
import com.example.marketing.common.config.ConfigSchemaPayload;
import com.example.marketing.common.config.ConfigType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 聚合各进程自述的 schema：这是后台"能改哪些参数"的唯一清单，而 admin 不 import 业务模块。
 *
 * <p>服务名是**固定候选集**而不是 SCAN 出来的：与 ④ 禁 SCAN 同源，"任意键名都能被发现"
 * 本身就是一个面。没上报的服务显式进 {@link #unreported()}，而不是静默少一个下拉项——
 * 运营看到"少了个参数"与看到"该服务未上报"是两回事。</p>
 *
 * <p>LITE 下业务模块的自述挂在 {@code marketing-standalone} 上，所以候选集里同时有它和
 * 五个独立进程名；每个键的归属取载荷里的 {@code owner}（模块名），不是进程名。</p>
 */
@Slf4j
@Component
public class ConfigSchemaReader {

    public static final List<String> SERVICES = List.of(
            "marketing-gateway", "marketing-activity", "marketing-coupon",
            "marketing-discount", "marketing-seckill", "marketing-admin", "marketing-standalone");

    private final StringRedisTemplate redis;

    public ConfigSchemaReader(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public List<ConfigDefinition> declared() {
        return new ArrayList<>(byKey().values());
    }

    public Optional<ConfigDefinition> find(String key) {
        return Optional.ofNullable(byKey().get(key));
    }

    /** key → 模块名（自述里的 owner；缺省时退回上报它的进程名） */
    public Map<String, String> serviceByKey() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String service : SERVICES) {
            ConfigSchemaPayload payload = read(service);
            if (payload == null || payload.definitions() == null) {
                continue;
            }
            for (Map<String, Object> raw : payload.definitions()) {
                out.putIfAbsent(String.valueOf(raw.get("key")),
                        String.valueOf(raw.getOrDefault("owner", payload.service())));
            }
        }
        return out;
    }

    /** 聚合 schema 的版本（取最新一份自述的 generatedAt），只用于快照条目的 defVer */
    public long schemaVersion() {
        long max = 0L;
        for (String service : SERVICES) {
            ConfigSchemaPayload payload = read(service);
            if (payload != null) {
                max = Math.max(max, payload.generatedAt());
            }
        }
        return max;
    }

    public List<String> unreported() {
        List<String> missing = new ArrayList<>();
        for (String service : SERVICES) {
            if (read(service) == null) {
                missing.add(service);
            }
        }
        return missing;
    }

    private Map<String, ConfigDefinition> byKey() {
        Map<String, ConfigDefinition> out = new LinkedHashMap<>();
        for (String service : SERVICES) {
            ConfigSchemaPayload payload = read(service);
            if (payload == null || payload.definitions() == null) {
                continue;
            }
            for (Map<String, Object> raw : payload.definitions()) {
                ConfigDefinition d = toDefinition(raw);
                ConfigDefinition previous = out.putIfAbsent(d.key(), d);
                if (previous != null && !previous.equals(d)) {
                    log.warn("[config] 同一个键被两个进程以不同边界声明: {}（{} 与 {}），以先读到的为准",
                            d.key(), previous, d);
                }
            }
        }
        return out;
    }

    private ConfigSchemaPayload read(String service) {
        try {
            return ConfigSchemaCodec.read(redis.opsForValue().get(ConfigKeys.schema(service)));
        } catch (Exception e) {
            log.warn("[config] 读取 {} 的 schema 自述失败: {}", service, e.toString());
            return null;
        }
    }

    private static ConfigDefinition toDefinition(Map<String, Object> raw) {
        ConfigType type = ConfigType.valueOf(String.valueOf(raw.getOrDefault("type", "STRING")));
        long min = raw.get("min") instanceof Number n ? n.longValue() : 0L;
        long max = raw.get("max") instanceof Number n ? n.longValue() : 255L;
        return new ConfigDefinition(String.valueOf(raw.get("key")), type, min, max,
                String.valueOf(raw.getOrDefault("defaultValue", "")),
                String.valueOf(raw.getOrDefault("description", "")));
    }
}
