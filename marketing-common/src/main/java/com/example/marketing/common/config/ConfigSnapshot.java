package com.example.marketing.common.config;

import java.util.Map;

/**
 * 一份自包含的全量配置快照。读方一次 GET 拿到全部生效值，
 * 因此不存在"改了 A 又改 B、读方拿到一新一旧"的半应用窗口。
 *
 * @param version     全局单调序号（来自 {@code mkt:cfg:seq}）
 * @param generatedAt 发布时刻（ISO-8601），只用于人看与 ④ 的"多久没更新"
 * @param defVer      发布时聚合到的 schema 版本，仅诊断用
 */
public record ConfigSnapshot(long version, String generatedAt, Map<String, Entry> entries) {

    public record Entry(String value, ConfigType type, long defVer) {
    }

    public static ConfigSnapshot empty() {
        return new ConfigSnapshot(0L, "", Map.of());
    }

    public ConfigSnapshot {
        entries = entries == null ? Map.of() : Map.copyOf(entries);
    }
}
