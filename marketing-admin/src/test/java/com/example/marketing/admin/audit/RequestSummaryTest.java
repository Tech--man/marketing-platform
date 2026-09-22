package com.example.marketing.admin.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计摘要的脱敏。审计表天生是"给一群人看的明文日志"，而后台请求里最多的敏感字段
 * 恰恰就是口令 —— 一次 /auth/password 的 body 原样入库，等于把全库明文口令编目成表。
 */
class RequestSummaryTest {

    @Test
    @DisplayName("JSON 里的口令类字段只留键名不留值")
    void redactsPasswordLikeKeysInJson() {
        String summary = RequestSummary.of("{\"username\":\"admin\",\"password\":\"rootdev123\"}");

        assertTrue(summary.contains("\"username\":\"admin\""), "非敏感字段要保持可读: " + summary);
        assertFalse(summary.contains("rootdev123"), "明文口令绝不能进审计: " + summary);
        assertTrue(summary.contains("password"), "键名保留才有排查价值: " + summary);
    }

    @Test
    @DisplayName("驼峰与下划线两种写法都盖住")
    void redactsCamelAndSnakeCase() {
        assertFalse(RequestSummary.of("{\"oldPassword\":\"abc12345\"}").contains("abc12345"));
        assertFalse(RequestSummary.of("{\"new_password\":\"abc12345\"}").contains("abc12345"));
        assertFalse(RequestSummary.of("{\"token\":\"abc12345\"}").contains("abc12345"));
        assertFalse(RequestSummary.of("{\"client_secret\":\"abc12345\"}").contains("abc12345"));
    }

    @Test
    @DisplayName("查询串里的敏感参数同样处理（URI 也会进审计）")
    void redactsQueryStringParams() {
        String summary = RequestSummary.of("?username=admin&password=abc12345");

        assertFalse(summary.contains("abc12345"), summary);
        assertTrue(summary.contains("username=admin"), summary);
    }

    @Test
    @DisplayName("超长摘要截断且可见，不静默丢一半")
    void capsLengthVisibly() {
        String summary = RequestSummary.of("x".repeat(900));

        assertTrue(summary.length() <= 600, "实际长度 " + summary.length());
        assertTrue(summary.endsWith("(truncated)"), "截断必须留下记号: " + summary);
    }

    @Test
    @DisplayName("空与非 JSON 输入都不炸，原样截断")
    void toleratesJunkInput() {
        assertEquals("", RequestSummary.of(null));
        assertEquals("", RequestSummary.of("   "));
        String junk = RequestSummary.of("not json at all, password=abc12345");
        assertFalse(junk.contains("abc12345"), junk);
    }
}
