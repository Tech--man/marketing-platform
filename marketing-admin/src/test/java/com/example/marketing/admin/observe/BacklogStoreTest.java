package com.example.marketing.admin.observe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 积压读数的真 SQL（H2 {@code MODE=MySQL}）。这一族断言管的是同一件事：
 * <b>"看不见"绝不能被算成"没有"</b>。
 *
 * <p>为什么值得单独写：admin 的 DataSource 在每服务一库档指向 {@code marketing_admin}，
 * 而那张库里<b>根本没有</b> {@code local_message}（另四个业务库各有一份）。
 * 如果 ④ 只查自己连接的库，它会永远报"积压 0"——那不是少报，是<b>把没有的东西报成健康</b>。
 * 所以这里既验跨库发现，也验"某个库读不到时要留痕"。</p>
 */
class BacklogStoreTest {

    private JdbcTemplate jdbc;
    private BacklogStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:ops_backlog;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP SCHEMA IF EXISTS marketing CASCADE");
        jdbc.execute("DROP SCHEMA IF EXISTS marketing_seckill CASCADE");
        jdbc.execute("CREATE SCHEMA marketing");
        jdbc.execute("CREATE SCHEMA marketing_seckill");
        for (String schema : List.of("marketing", "marketing_seckill")) {
            jdbc.execute("CREATE TABLE " + schema + ".local_message ("
                    + " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " topic VARCHAR(64) NOT NULL,"
                    + " biz_key VARCHAR(128) NOT NULL,"
                    + " status VARCHAR(16) NOT NULL,"
                    + " retry_count INT NOT NULL DEFAULT 0,"
                    + " next_retry_time TIMESTAMP NULL,"
                    + " create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
        store = new BacklogStore(jdbc);
    }

    private void insert(String schema, String status) {
        jdbc.update("INSERT INTO " + schema + ".local_message (topic, biz_key, status) VALUES (?,?,?)",
                "MKT_COUPON_GRANT", schema + "-" + System.nanoTime(), status);
    }

    @Test
    @DisplayName("发现的库只包含真的有这张表的库，且不含 information_schema")
    void discoversSchemasHoldingTheTable() {
        List<String> schemas = store.schemasWithTable();
        // 大小写不敏感：H2 把非引号标识符存成大写，MySQL 按 lower_case_table_names 可能是小写。
        // 生产侧不需要这个宽容（qualified() 用的就是 information_schema 原样返回的名字）
        assertTrue(containsIgnoreCase(schemas, "marketing"), "应发现 marketing，实际 " + schemas);
        assertTrue(containsIgnoreCase(schemas, "marketing_seckill"),
                "两个库都有该表就要都查到，只查连接库是静默少报");
        assertTrue(!containsIgnoreCase(schemas, "information_schema"),
                "系统库不能进清单: " + schemas);
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        return list.stream().anyMatch(v -> v.equalsIgnoreCase(value));
    }

    @Test
    @DisplayName("按状态计数：pendingSent 是 PENDING+SENT，不是全部")
    void countsByStatus() {
        insert("marketing", "PENDING");
        insert("marketing", "SENT");
        insert("marketing", "CONFIRMED");
        insert("marketing", "FAILED");

        SchemaBacklog row = store.backlog().stream()
                .filter(b -> b.schema().equalsIgnoreCase("marketing")).findFirst().orElseThrow();

        assertEquals(2L, row.pendingSent(), "PENDING + SENT 才是没排空的那部分");
        assertEquals(1L, row.statusCounts().get("FAILED"));
        assertEquals(1L, row.statusCounts().get("CONFIRMED"),
                "CONFIRMED 是历史堆积，不能被算进积压");
        assertNull(row.error());
    }

    @Test
    @DisplayName("没见过的状态值照样带出来：加状态的人不该被白名单静默吃掉")
    void unknownStatusStillReported() {
        insert("marketing", "SOMETHING_NEW");

        SchemaBacklog row = store.backlog().stream()
                .filter(b -> b.schema().equalsIgnoreCase("marketing")).findFirst().orElseThrow();

        assertEquals(1L, row.statusCounts().get("SOMETHING_NEW"));
        assertEquals(0L, row.pendingSent());
    }

    @Test
    @DisplayName("两个库各报各的，不被并成一个数")
    void perSchemaRows() {
        insert("marketing", "PENDING");
        insert("marketing_seckill", "PENDING");
        insert("marketing_seckill", "PENDING");

        List<SchemaBacklog> rows = store.backlog();
        assertEquals(2, rows.size());
        assertEquals(1L, rows.stream().filter(r -> r.schema().equalsIgnoreCase("marketing"))
                .findFirst().orElseThrow().pendingSent());
        assertEquals(2L, rows.stream().filter(r -> r.schema().equalsIgnoreCase("marketing_seckill"))
                .findFirst().orElseThrow().pendingSent());
    }

    @Test
    @DisplayName("某个库读不到时留痕并继续：一个库的权限问题不能让整个积压消失")
    void perSchemaFailureIsMarkedNotSwallowed() {
        insert("marketing", "PENDING");
        store = new BacklogStore(jdbc) {
            @Override
            Map<String, Long> statusCounts(String schema) {
                if (schema.equalsIgnoreCase("marketing_seckill")) {
                    throw new org.springframework.dao.PermissionDeniedDataAccessException("denied", null);
                }
                return super.statusCounts(schema);
            }
        };

        List<SchemaBacklog> rows = store.backlog();
        SchemaBacklog broken = rows.stream()
                .filter(r -> r.schema().equalsIgnoreCase("marketing_seckill")).findFirst().orElseThrow();
        SchemaBacklog ok = rows.stream()
                .filter(r -> r.schema().equalsIgnoreCase("marketing")).findFirst().orElseThrow();

        assertNotNull(broken.error(), "读不到的库必须带着原因出现，而不是少一行");
        assertEquals(1L, ok.pendingSent(), "另一个库的读数不能被连坐");
        assertTrue(broken.pendingSent() < 0, "UNKNOWN 用 -1 表示，不能是 0：" + broken.pendingSent());
    }

    @Test
    @DisplayName("库名不合标识符形状就不拼进 SQL：清单虽来自 information_schema，也不是可信输入")
    void refusesNonIdentifierSchemaNames() {
        BacklogStore s = new BacklogStore(jdbc);
        for (String bad : java.util.Arrays.asList("marketing; DROP TABLE x", "a-b", "", null)) {
            assertThrows(RuntimeException.class, () -> s.statusCounts(bad),
                    "必须被拒绝而不是被执行: " + bad);
        }
    }

    @Test
    @DisplayName("死信只取限量样本，且带 topic/bizKey 供人排查")
    void deadLettersAreBoundedSamples() {
        insert("marketing", "FAILED");
        insert("marketing", "FAILED");

        SchemaBacklog row = store.backlog().stream()
                .filter(b -> b.schema().equalsIgnoreCase("marketing")).findFirst().orElseThrow();

        assertEquals(2L, row.statusCounts().get("FAILED"));
        assertEquals(2, row.deadLetters().size(), "两条死信都该在样本里（上限 5）");
        assertTrue(row.deadLetters().get(0).contains("MKT_COUPON_GRANT"));
    }
}
