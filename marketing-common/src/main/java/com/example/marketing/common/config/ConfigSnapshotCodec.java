package com.example.marketing.common.config;

import com.example.marketing.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Map;

/**
 * 快照 JSON 编解码。读侧永不抛：坏载荷退化成空快照（全部回出厂值），
 * 而不是让轮询线程带伤抱着上一份陈旧值。
 */
public final class ConfigSnapshotCodec {

    private static final TypeReference<Map<String, Object>> ROOT = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, ConfigSnapshot.Entry>> ENTRIES = new TypeReference<>() {
    };

    public static String write(ConfigSnapshot snapshot) {
        return JsonUtils.toJson(snapshot);
    }

    public static ConfigSnapshot read(String json) {
        if (json == null || json.isBlank()) {
            return ConfigSnapshot.empty();
        }
        try {
            Map<String, Object> root = JsonUtils.parse(json, ROOT);
            if (root == null) {
                return ConfigSnapshot.empty();
            }
            long version = root.get("version") instanceof Number n ? n.longValue() : 0L;
            Object generatedAt = root.get("generatedAt");
            Map<String, ConfigSnapshot.Entry> entries = root.get("entries") == null
                    ? Map.of()
                    : JsonUtils.parse(JsonUtils.toJson(root.get("entries")), ENTRIES);
            return new ConfigSnapshot(version, generatedAt == null ? "" : generatedAt.toString(), entries);
        } catch (Exception e) {
            return ConfigSnapshot.empty();
        }
    }

    private ConfigSnapshotCodec() {
    }
}
