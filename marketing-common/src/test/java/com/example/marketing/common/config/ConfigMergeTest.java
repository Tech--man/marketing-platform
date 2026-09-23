package com.example.marketing.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 生效优先级：当前 form 的行 &gt; GLOBAL 的行 &gt; 没有（由调用方退回出厂值）。
 *
 * <p>关键是"不串"：FULL 进程读到 LITE 的保守阈值会掐死吞吐，反过来是把洪流灌进单机。
 * 拆成三条独立断言而不是一个综合用例，将来改 merge 时红字能指明是哪一侧漏了。</p>
 */
class ConfigMergeTest {

    private static final List<ConfigMerge.Row> ROWS = List.of(
            new ConfigMerge.Row("k.global", "GLOBAL", "1"),
            new ConfigMerge.Row("k.lite", "LITE", "2"),
            new ConfigMerge.Row("k.full", "FULL", "3"),
            new ConfigMerge.Row("k.both", "GLOBAL", "10"),
            new ConfigMerge.Row("k.both", "LITE", "20"));

    @Test
    @DisplayName("LITE 读到 GLOBAL 与自己的行，同键时自己的行覆盖 GLOBAL")
    void liteSeesOwnAndGlobal() {
        Map<String, String> merged = ConfigMerge.merge("LITE", ROWS);
        assertEquals("1", merged.get("k.global"));
        assertEquals("20", merged.get("k.both"));
        assertEquals("2", merged.get("k.lite"));
        assertFalse(merged.containsKey("k.full"), "FULL 的值不得进入 LITE 的生效集");
    }

    @Test
    @DisplayName("未设置形态时只有 GLOBAL 生效")
    void unsetFormOnlySeesGlobal() {
        assertEquals(Map.of("k.global", "1", "k.both", "10"),
                ConfigMerge.merge(ConfigKeys.GLOBAL, ROWS));
    }

    @Test
    @DisplayName("大小写与空白无关；DEV 看不到 LITE/FULL 的行")
    void caseInsensitiveAndDevIsolated() {
        Map<String, String> full = ConfigMerge.merge(" full ", ROWS);
        assertEquals("3", full.get("k.full"));
        assertFalse(full.containsKey("k.lite"));
        assertEquals(Map.of("k.global", "1", "k.both", "10"), ConfigMerge.merge("DEV", ROWS));
    }
}
