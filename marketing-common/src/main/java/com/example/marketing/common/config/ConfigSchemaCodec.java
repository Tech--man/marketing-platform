package com.example.marketing.common.config;

import com.example.marketing.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * schema 载荷编解码。读侧坏载荷返回 null：admin 面对一份写错的自述，
 * 应当"看不见这个服务的参数"并把它列进 unreported，而不是整个配置页 500。
 */
public final class ConfigSchemaCodec {

    public static String write(ConfigSchemaPayload payload) {
        return JsonUtils.toJson(payload);
    }

    public static ConfigSchemaPayload read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.parse(json, new TypeReference<ConfigSchemaPayload>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private ConfigSchemaCodec() {
    }
}
