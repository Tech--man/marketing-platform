package com.example.marketing.common.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 载荷必须与 {@code admin_audit_log} 的列一一对应：drain 落表时字段错位是<b>静默</b>的
 * （表照样写进去，只是 actorName 里躺着 role），比不落更难发现。
 */
class AuditPayloadCodecTest {

    private static final AuditPayload SAMPLE = new AuditPayload(
            1L, "admin", "admin", "activity.budget.set", "activity", "ACT2026001",
            "PUT", "/api/admin/activities/ACT2026001/budget",
            "from=70.00, to=80.00, version=3", 0, "", "127.0.0.1", 12L, 1_800_000_000L);

    @Test
    @DisplayName("写出读回逐字段相等")
    void roundTripsEveryField() {
        AuditPayload back = AuditPayloadCodec.read(AuditPayloadCodec.write(SAMPLE)).orElseThrow();
        assertEquals(SAMPLE, back);
    }

    @Test
    @DisplayName("坏 JSON / 空串 / null 一律读成 empty，不抛")
    void readNeverThrows() {
        assertTrue(AuditPayloadCodec.read("{not json").isEmpty());
        assertTrue(AuditPayloadCodec.read("").isEmpty());
        assertTrue(AuditPayloadCodec.read(null).isEmpty());
    }

    @Test
    @DisplayName("缺字段的载荷仍可读回（逐字段兜底），不能整条抛掉")
    void partialPayloadDegradesRatherThanThrows() {
        AuditPayload back = AuditPayloadCodec.read("{\"action\":\"activity.gray.set\"}").orElseThrow();
        assertEquals("activity.gray.set", back.action());
        assertEquals("", back.actorName());
        assertEquals(0L, back.epochSecond());
    }

    @Test
    @DisplayName("载荷里没有未脱敏的 body 字段：只有 requestSummary")
    void summaryIsCarriedAsIs() {
        String json = AuditPayloadCodec.write(SAMPLE);
        assertTrue(json.contains("from=70.00"), json);
        assertFalse(json.contains("\"body\""), "不许另开一个未脱敏的 body 字段: " + json);
    }
}
