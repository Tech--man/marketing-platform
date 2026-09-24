package com.example.marketing.admin.observe;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 审计表自身的统计（"该不该谈保留期"这个问题的读数）。
 *
 * <p>用 JdbcTemplate 而不是 {@code AdminAuditLogMapper}：{@code BaseMapper} 只能 count 与分页取行，
 * 而这里要的是一条 group by 与"最老一行"。仓里没有任何 mapper XML，引一份只为这两个数不值。</p>
 */
@Slf4j
@Repository
public class AuditTableStore {

    private static final int TOP_ACTIONS = 8;

    private final JdbcTemplate jdbc;

    public AuditTableStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public OpsSnapshotView.AuditTableView stats() {
        try {
            Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log", Long.class);
            List<String> oldest = jdbc.queryForList(
                    "SELECT DATE_FORMAT(MIN(create_time), '%Y-%m-%dT%H:%i:%s') FROM admin_audit_log",
                    String.class);
            List<Map<String, Object>> top = jdbc.queryForList(
                    "SELECT action, COUNT(*) AS n FROM admin_audit_log GROUP BY action"
                            + " ORDER BY n DESC LIMIT " + TOP_ACTIONS);
            return new OpsSnapshotView.AuditTableView(rows == null ? 0L : rows,
                    oldest.isEmpty() ? null : oldest.get(0), top, null);
        } catch (DataAccessException e) {
            // 只读面不能因为一张表读不动就整片 500；但也绝不给一个"0 行"的空视图
            log.warn("[ops] 审计表统计读不到: {}", e.toString());
            return new OpsSnapshotView.AuditTableView(-1L, null, List.of(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
