package com.example.marketing.admin.config;

import com.example.marketing.common.config.ConfigMerge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真值表存取的 SQL 语义（H2 {@code MODE=MySQL}，跑真 SQL 而不是 mapper 桩）。
 *
 * <p>三条都是后台写路径的地基：同键同形态必须覆盖（否则一次改阈值就长出十行）、
 * 同键不同形态必须共存（两档共库靠的就是这个）、删行必须能报出"真的删掉了没有"
 * （恢复出厂的语义全靠它）。</p>
 */
class AdminConfigStoreTest {

    private AdminConfigStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:admin_config_store;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS admin_config");
        jdbc.execute("""
                CREATE TABLE admin_config (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    cfg_key VARCHAR(64) NOT NULL,
                    form VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',
                    cfg_value VARCHAR(255) NOT NULL,
                    version BIGINT NOT NULL DEFAULT 0,
                    updated_by VARCHAR(64) NOT NULL DEFAULT '',
                    remark VARCHAR(255) NOT NULL DEFAULT '',
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uk_key_form UNIQUE (cfg_key, form))""");
        store = new AdminConfigStore(jdbc);
    }

    @Test
    @DisplayName("同键同形态再写是覆盖而不是加行，version/updated_by/remark 一起走")
    void upsertOverridesSameKeyForm() {
        store.upsert("k", "LITE", "120", 1L, "admin", "第一次");
        store.upsert("k", "LITE", "150", 2L, "operator", "改一下");
        assertEquals(1, store.rows().size());
        assertEquals("150", store.findValue("k", "LITE"));
        AdminConfigStore.Row row = store.detail().get(0);
        assertEquals("2", row.version());
        assertEquals("改一下", row.remark());
        assertEquals("operator", row.updatedBy());
    }

    @Test
    @DisplayName("同键不同形态是两行，合并后各读各的（分形态阈值共存于一张表）")
    void differentFormsCoexist() {
        store.upsert("k", "LITE", "120", 1L, "admin", "");
        store.upsert("k", "FULL", "1000", 2L, "admin", "");
        List<ConfigMerge.Row> rows = store.rows();
        assertEquals(2, rows.size());
        assertEquals("120", ConfigMerge.merge("LITE", rows).get("k"));
        assertEquals("1000", ConfigMerge.merge("FULL", rows).get("k"));
        assertTrue(ConfigMerge.merge("DEV", rows).isEmpty(),
                "只配了 LITE 与 FULL 时，DEV 档什么都读不到（由调用方退回出厂值）");
    }

    @Test
    @DisplayName("delete 命中返回 1、未命中返回 0（恢复出厂就是删行）")
    void deleteReportsAffectedRows() {
        store.upsert("k", "GLOBAL", "1", 1L, "admin", "");
        assertEquals(1, store.delete("k", "GLOBAL"));
        assertNull(store.findValue("k", "GLOBAL"));
        assertEquals(0, store.delete("k", "GLOBAL"));
        assertTrue(store.rows().isEmpty());
    }

    @Test
    @DisplayName("P1：行不存在 + expectedVersion=0 是真首写——必须落行，不能静默返回成功")
    void casFirstWritePersistsRow() {
        boolean ok = store.upsertCas("k", "GLOBAL", "120", 5L, "admin", "脚本首写", 0L);

        assertEquals(true, ok, "首写协议路径应成功");
        assertEquals("120", store.findValue("k", "GLOBAL"), "原实现返回 true 但什么都没写（广播/审计/回 200 全套照走的 no-op）");
        assertEquals(Long.valueOf(5L), store.findRowVersion("k", "GLOBAL"));
    }

    @Test
    @DisplayName("行不存在 + expectedVersion 非 0 → false（行被人删了的过期信息）")
    void casAbsentRowWithNonZeroVersionRejected() {
        assertEquals(false, store.upsertCas("k", "GLOBAL", "120", 5L, "admin", "", 3L));
        assertNull(store.findValue("k", "GLOBAL"));
    }

    @Test
    @DisplayName("行存在但 version 不匹配 → false（41008 交给调用方）")
    void casStaleVersionRejected() {
        store.upsert("k", "GLOBAL", "100", 1L, "admin", "");
        assertEquals(false, store.upsertCas("k", "GLOBAL", "120", 2L, "admin", "", 0L));
        assertEquals("100", store.findValue("k", "GLOBAL"));
    }
}
