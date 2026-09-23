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

    public int delete(String key, String form) {
        return jdbc.update("DELETE FROM admin_config WHERE cfg_key = ? AND form = ?", key, form);
    }
}
