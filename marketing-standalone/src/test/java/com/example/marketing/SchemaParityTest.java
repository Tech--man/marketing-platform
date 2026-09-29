package com.example.marketing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DDL 两副本一致性守卫（2026-09-29 审查第五批）。
 *
 * <p>同一套表结构在仓内有 init 与 init-lite 两份副本（另有 migrate 按需复制段落），
 * 纪律只靠注释——2026-09-25 已真实漂移咬过一次（init 补了 consumer 建库段、
 * init-lite 没跟上，新卷 init 整体中断）。本测试把"两份必须一致"变成红的用例：
 * 逐表比对 CREATE TABLE 块（库名/建库语句/种子 INSERT 不在比对范围——那是两份
 * 刻意不同的部分），任何一侧改表忘了另一侧，这里立刻红。</p>
 */
class SchemaParityTest {

    private static final Pattern TABLE_BLOCK = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS (\\w+) \\((.*?)\\) ENGINE", Pattern.DOTALL);

    private Map<String, String> tablesOf(Path file) throws Exception {
        String sql = Files.readString(file);
        // 注释剥除：列 COMMENT 里的中文保留（两份同源），只剥可能与行号/时间相关的
        // 尾随空白差异；这里直接比对原始块文本（两份本就应逐字一致）
        Matcher m = TABLE_BLOCK.matcher(sql);
        Map<String, String> out = new LinkedHashMap<>();
        while (m.find()) {
            out.put(m.group(1), m.group(2).replaceAll("\\s+", " ").trim());
        }
        return out;
    }

    @Test
    @DisplayName("init 与 init-lite 的表定义逐表一致：改一边忘另一边在这里变红")
    void tableDefinitionsMatchAcrossCopies() throws Exception {
        Path root = Path.of("..");
        Path init = root.resolve("docker/mysql/init/01-schema.sql");
        Path lite = root.resolve("docker/mysql/init-lite/01-schema-lite.sql");
        assertTrue(Files.exists(init), "找不到 " + init + "（工作目录应为模块目录）");
        assertTrue(Files.exists(lite), "找不到 " + lite);

        Map<String, String> a = tablesOf(init);
        Map<String, String> b = tablesOf(lite);
        assertTrue(a.size() >= 15, "init 解析出的表少得反常: " + a.size());

        List<String> onlyInInit = a.keySet().stream().filter(k -> !b.containsKey(k)).toList();
        List<String> onlyInLite = b.keySet().stream().filter(k -> !a.containsKey(k)).toList();
        assertTrue(onlyInInit.isEmpty(), "init 独有的表（init-lite 漏建，新 LITE 卷起不来）: " + onlyInInit);
        assertTrue(onlyInLite.isEmpty(), "init-lite 独有的表: " + onlyInLite);

        List<String> diff = a.entrySet().stream()
                .filter(e -> !e.getValue().equals(b.get(e.getKey())))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        assertTrue(diff.isEmpty(),
                "表定义漂移（改了一份忘了另一份——LITE 与 FULL 容器档从新卷起步就是两套结构）: " + diff);
    }

    @Test
    @DisplayName("守卫自检：构造块解析至少抓到幂等表与秒杀单（防正则失效假装通过）")
    void parserActuallyParsesKnownTables() throws Exception {
        Map<String, String> a = tablesOf(Path.of("../docker/mysql/init/01-schema.sql"));
        assertEquals("idempotent_record", a.keySet().stream().filter(k -> k.contains("idempotent")).findFirst().orElse(""));
        assertTrue(a.containsKey("seckill_order"));
        assertTrue(a.get("seckill_order").contains("active"), "seckill_order 应含 H7 的 active 列");
    }
}
