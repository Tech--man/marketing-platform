package com.example.marketing.common.reheat;

import com.example.marketing.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Map;
import java.util.Optional;

/** 重预热载荷的 Redis 编解码。读侧永不抛（与 ⑤ 的快照编解码、③ 的审计载荷同一纪律）。 */
public final class ReheatCodec {

    public static final String FIELD = "payload";

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    public static String writeRequest(ReheatPayloads.Request r) {
        return JsonUtils.toJson(r);
    }

    public static String writeAck(ReheatPayloads.Ack a) {
        return JsonUtils.toJson(a);
    }

    public static Optional<ReheatPayloads.Request> readRequest(String json) {
        Map<String, Object> m = parse(json);
        if (m == null) {
            return Optional.empty();
        }
        return Optional.of(new ReheatPayloads.Request(
                str(m.get("id")), str(m.get("type")), str(m.get("key")),
                Boolean.TRUE.equals(m.get("force")) || "true".equals(str(m.get("force"))),
                str(m.get("actor")), longOrZero(m.get("requestedAt"))));
    }

    public static Optional<ReheatPayloads.Ack> readAck(String json) {
        Map<String, Object> m = parse(json);
        if (m == null) {
            return Optional.empty();
        }
        return Optional.of(new ReheatPayloads.Ack(
                str(m.get("id")), str(m.get("type")), str(m.get("key")), str(m.get("status")),
                longOrZero(m.get("before")), longOrZero(m.get("after")), str(m.get("error")),
                longOrZero(m.get("atEpoch"))));
    }

    private static Map<String, Object> parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return JsonUtils.parse(json, MAP);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static long longOrZero(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private ReheatCodec() {
    }
}
