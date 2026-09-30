package com.example.marketing.admin.config;

import com.example.marketing.common.config.ConfigMerge;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@code admin_config} 的存取。
 *
 * <p>用 JdbcTemplate 而不是 BaseMapper：这里要的只是一条 upsert 与两个整体读，而 JdbcTemplate
 * 能被 H2 真 SQL 测到——mapper 桩测不到 SQL 语义，而这恰是本表最容易出错的地方（唯一键作用域、
 * 删行的命中与否）。与 {@code IdempotentExecutor} / {@code LocalMessageService} 同一手法。</p>
 */
@Repository
public class AdminConfigStore {

    /** 展示行：版本、操作人与备注（④ 的"期望值 vs 生效值"对比也要用它） */
    public record Row(String cfgKey, String form, String value, String version,
                      String updatedBy, String remark) {
    }

    private final JdbcTemplate jdbc;

    public AdminConfigStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ConfigMerge.Row> rows() {
        return jdbc.query("SELECT cfg_key, form, cfg_value FROM admin_config ORDER BY cfg_key, form",
                (rs, i) -> new ConfigMerge.Row(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    public List<Row> detail() {
        return jdbc.query("SELECT cfg_key, form, cfg_value, version, updated_by, remark "
                        + "FROM admin_config ORDER BY cfg_key, form",
                (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)));
    }

    public String findValue(String key, String form) {
        List<String> found = jdbc.queryForList(
                "SELECT cfg_value FROM admin_config WHERE cfg_key = ? AND form = ?",
                String.class, key, form);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 先 UPDATE 再 INSERT：{@code ON DUPLICATE KEY UPDATE} 在 H2 的 MySQL 模式下语义不完全一致，
     * 而这个方法必须有单测覆盖。并发插入撞唯一键时重跑一次 UPDATE，不重试第二次 INSERT。
     */
    public void upsert(String key, String form, String value, long version, String by, String remark) {
        if (jdbc.update("UPDATE admin_config SET cfg_value = ?, version = ?, updated_by = ?, remark = ? "
                + "WHERE cfg_key = ? AND form = ?", value, version, by, remark, key, form) > 0) {
            return;
        }
        try {
            jdbc.update("INSERT INTO admin_config (cfg_key, form, cfg_value, version, updated_by, remark) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", key, form, value, version, by, remark);
        } catch (DuplicateKeyException raced) {
            jdbc.update("UPDATE admin_config SET cfg_value = ?, version = ?, updated_by = ?, remark = ? "
                    + "WHERE cfg_key = ? AND form = ?", value, version, by, remark, key, form);
        }
    }

    /**
     * 带 CAS 的写入（2026-09-29 审查第五批）：expectedVersion 非空时与行上 version
     * 比对，不一致返回 false（调用方回 41008）。行不存在 + expectedVersion 非空
     * 视为过期信息（行被人删了）同样 false；行不存在 + expectedVersion==0 视为
     * "客户端确认过没有覆盖"的首写，放行。
     */
    /** 行上当前 version；行不存在返回 null（测试与 CAS 判定共用） */
    public Long findRowVersion(String key, String form) {
        var found = jdbc.queryForList(
                "SELECT version FROM admin_config WHERE cfg_key = ? AND form = ?", key, form);
        return found.isEmpty() ? null : ((Number) found.get(0).get("version")).longValue();
    }

    public boolean upsertCas(String key, String form, String value, long version, String by,
                             String remark, Long expectedVersion) {
        if (expectedVersion == null) {
            upsert(key, form, value, version, by, remark);
            return true;
        }
        int updated = jdbc.update("UPDATE admin_config SET cfg_value = ?, version = ?, updated_by = ?, remark = ? "
                + "WHERE cfg_key = ? AND form = ? AND version = ?",
                value, version, by, remark, key, form, expectedVersion);
        if (updated > 0) {
            return true;
        }
        // P1（2026-09-30 第二轮复审）：行不存在 + expectedVersion==0 是"客户端确认过
        // 没有覆盖"的首写——必须真的写进去。原实现在这里直接 return true 而没有任何
        // 写入：调用方照常广播/审计/回 200，配置却根本没落库（脚本首写协议路径静默 no-op）。
        // INSERT 撞并发首写时按冲突返回 false（41008 语义正确）。
        if (findRowVersion(key, form) == null && expectedVersion == 0L) {
            try {
                jdbc.update("INSERT INTO admin_config (cfg_key, form, cfg_value, version, updated_by, remark) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", key, form, value, version, by, remark);
                return true;
            } catch (DuplicateKeyException raced) {
                return false;
            }
        }
        return false;
    }

    public int delete(String key, String form) {
        return jdbc.update("DELETE FROM admin_config WHERE cfg_key = ? AND form = ?", key, form);
    }
}
