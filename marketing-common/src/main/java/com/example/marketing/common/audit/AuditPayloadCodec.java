package com.example.marketing.common.audit;

import com.example.marketing.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Map;
import java.util.Optional;

/**
 * 审计载荷的 Redis 编解码：字段名固定 {@code payload}，值是一段 JSON。
 *
 * <p>读侧永不抛（与 ⑤ 的 {@code ConfigSnapshotCodec} 同一纪律）：一条脏数据不能让整条
 * drain 停摆，那样后果比丢一条审计严重得多。跳过的那条由调用方计数并告警。</p>
 */
public final class AuditPayloadCodec {

    public static final String FIELD = "payload";

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    public static String write(AuditPayload p) {
        return JsonUtils.toJson(p);
    }

    public static Optional<AuditPayload> read(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> m = JsonUtils.parse(json, MAP);
            return Optional.of(new AuditPayload(
                    longOrNull(m.get("actorId")), str(m.get("actorName")), str(m.get("role")),
                    str(m.get("action")), str(m.get("resourceType")), str(m.get("resourceId")),
                    str(m.get("method")), str(m.get("path")), str(m.get("requestSummary")),
                    intOrZero(m.get("resultCode")), str(m.get("errorMsg")), str(m.get("ip")),
                    longOrZero(m.get("costMs")), longOrZero(m.get("epochSecond"))));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static Long longOrNull(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static long longOrZero(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static int intOrZero(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    private AuditPayloadCodec() {
    }
}
