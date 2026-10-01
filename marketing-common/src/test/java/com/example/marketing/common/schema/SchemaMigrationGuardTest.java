package com.example.marketing.common.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * schema 迁移断言的守卫语义（H2 真 SQL，不 mock JdbcTemplate——判定逻辑本身
 * 查 information_schema，mock 掉它等于把被测逻辑 stub 成常量）。
 *
 * <p>变异口径：把 afterSingletonsInstantiated 里的"缺列抛异常"改成"缺列只 WARN"，
 * missingColumnFailsStartup / missingTableFailsStartup 必须红；把 required-columns
 * 解析改成无条件忽略，emptyDeclarationSkipsQuery 必须仍绿而其余仍红。</p>
 */
class SchemaMigrationGuardTest {

    /** 建一张带声明列的表 + 一张不带声明列的表（后者是"缺列"用例的现成形态） */
    private JdbcTemplate db(String ddl) {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:schema_guard_test;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(ds);
        jdbcTemplate.execute("DROP TABLE IF EXISTS idempotent_record");
        jdbcTemplate.execute("DROP TABLE IF EXISTS admin_audit_log");
        if (ddl != null) {
            jdbcTemplate.execute(ddl);
        }
        return jdbcTemplate;
    }

    @Test
    @DisplayName("声明的列都在：放行（不抛）")
    void declaredColumnsPresentPasses() {
        JdbcTemplate jdbcTemplate = db("""
                CREATE TABLE idempotent_record (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    biz_key VARCHAR(128) NOT NULL,
                    claim_token VARCHAR(36) NULL
                )""");
        SchemaMigrationGuard guard = new SchemaMigrationGuard(jdbcTemplate,
                List.of("idempotent_record.claim_token"));
        assertDoesNotThrow(guard::afterSingletonsInstantiated);
    }

    @Test
    @DisplayName("缺 claim_token 列：启动失败，报错点名缺失列并给出 migrate.sh 处置")
    void missingColumnFailsStartup() {
        // 列名故意写成 claimtoken：模拟"迁移没跑"的旧表形状
        JdbcTemplate jdbcTemplate = db("""
                CREATE TABLE idempotent_record (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    biz_key VARCHAR(128) NOT NULL,
                    claimtoken VARCHAR(36) NULL
                )""");
        SchemaMigrationGuard guard = new SchemaMigrationGuard(jdbcTemplate,
                List.of("idempotent_record.claim_token"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                guard::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains("claim_token"),
                "报错必须点名缺失列，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("migrate.sh"),
                "报错必须给出可执行的处置命令，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("声明的表整表缺失：同样启动失败（空卷/错库都不许带病上线）")
    void missingTableFailsStartup() {
        JdbcTemplate jdbcTemplate = db(null);
        SchemaMigrationGuard guard = new SchemaMigrationGuard(jdbcTemplate,
                List.of("admin_audit_log.source_id"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                guard::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains("admin_audit_log"),
                "报错必须点名缺失表，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("未声明必需列的服务（account）：零断言直接放行，库里什么都没有也不炸")
    void emptyDeclarationSkipsQuery() {
        JdbcTemplate jdbcTemplate = db(null);
        SchemaMigrationGuard guard = new SchemaMigrationGuard(jdbcTemplate, List.of());
        assertDoesNotThrow(guard::afterSingletonsInstantiated);
    }

    @Test
    @DisplayName("配置格式写错（没有列名/没有点）：启动失败而不是静默忽略该条")
    void malformedEntryFailsFast() {
        JdbcTemplate jdbcTemplate = db(null);
        SchemaMigrationGuard guard = new SchemaMigrationGuard(jdbcTemplate,
                List.of("idempotent_record."));
        assertThrows(IllegalStateException.class, guard::afterSingletonsInstantiated);
    }
}
