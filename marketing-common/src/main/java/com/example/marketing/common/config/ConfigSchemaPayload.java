package com.example.marketing.common.config;

import java.util.List;
import java.util.Map;

/**
 * 某个服务自述的可改参数清单（Redis 载荷）。
 *
 * <p>用 {@code List<Map>} 而不是 {@code List<ConfigDefinition>}：Map 的键集合就是协议本身，
 * 生产端加字段（比如后来的 {@code owner}）不会让旧版读方反序列化失败，
 * 而 record 加字段会让所有旧载荷读不出来。</p>
 */
public record ConfigSchemaPayload(long generatedAt, String service, List<Map<String, Object>> definitions) {
}
