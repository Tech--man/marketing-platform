package com.example.marketing.activity.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灰度真值在 DB 列（不走 Redis 通知）。四条各钉一个"会静默出事"的点：
 * 未配灰度的行不参与（否则"新建活动全量放行"的既有语义被打破）、越界值被钳位
 * （150% 会意外变成全量放行）、CSV 能解析、DB 读失败时保住上一次规则。
 */
class GrayRuleCacheTest {

    private JdbcTemplate jdbc;
    private GrayRuleCache cache;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:gray_rule_cache;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS activity");
        jdbc.execute("""
                CREATE TABLE activity (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    activity_no VARCHAR(64) NOT NULL,
                    gray_percent INT NULL,
                    gray_whitelist VARCHAR(255) NULL)""");
        cache = new GrayRuleCache(jdbc, 5);
    }

    private void insert(String no, Integer percent, String whitelist) {
        jdbc.update("INSERT INTO activity (activity_no, gray_percent, gray_whitelist) VALUES (?, ?, ?)",
                no, percent, whitelist);
    }

    @Test
    @DisplayName("只有配了灰度的行进规则表；CSV 白名单含空格也能解析")
    void loadsOnlyConfiguredRowsAndParsesCsv() {
        insert("A1", 5, "70001, 70002");
        insert("A2", null, null);
        cache.refreshNow();
        Optional<GrayRuleCache.Rule> r = cache.rule("A1");
        assertTrue(r.isPresent());
        assertEquals(5, r.get().percent());
        assertEquals(Set.of(70001L, 70002L), r.get().whitelist());
        assertFalse(cache.rule("A2").isPresent(), "未配灰度必须落到'没有规则'，让 hit() 走全量放行");
    }

    @Test
    @DisplayName("越界的 gray_percent 被钳到 [0,100]：150 不能变成意外全量")
    void percentIsClamped() {
        insert("A1", 150, null);
        insert("A2", -3, null);
        cache.refreshNow();
        assertEquals(100, cache.rule("A1").orElseThrow().percent());
        assertEquals(0, cache.rule("A2").orElseThrow().percent());
    }

    @Test
    @DisplayName("白名单里的非数字项被跳过，其余照常生效")
    void badCsvEntriesSkipped() {
        insert("A1", 5, "70001, oops, 70003");
        cache.refreshNow();
        assertEquals(Set.of(70001L, 70003L), cache.rule("A1").orElseThrow().whitelist());
    }

    @Test
    @DisplayName("回源失败时保住上一次规则（DB 抖一下不能把灰度打回全量）")
    void refreshFailureKeepsPreviousRules() {
        insert("A1", 5, null);
        cache.refreshNow();
        jdbc.execute("DROP TABLE activity");
        cache.refreshNow();
        assertTrue(cache.rule("A1").isPresent(), "读不到 DB 时要继续用旧规则，而不是清空");
    }
}
