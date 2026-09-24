package com.example.marketing.admin.observe;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code local_message} 的积压读数。
 *
 * <p><b>为什么要跨库找</b>：admin 自己的 DataSource 在每服务一库档指向
 * {@code marketing_admin}，而那张库里<b>根本没有</b> {@code local_message}
 * （四个业务库各一份）。只查连接库的话，④ 会永远报"积压 0"——那不是少报，
 * 是把没有的东西报成健康。所以库清单来自 {@code information_schema}，逐库查。</p>
 *
 * <p>{@code information_schema} 不需要额外授权（MySQL 本来就把它对连接可见，
 * 且只显示有权限的库），因此这里不用"先探本库再跨库"的两段式：一条查询覆盖两种形态，
 * 少一个分支就少一处会错的地方。</p>
 */
@Slf4j
@Repository
public class BacklogStore {

    static final String TABLE = "local_message";
    private static final int DEAD_LETTER_SAMPLE = 5;

    /** 库名只接受合法标识符。清单虽来自 information_schema 而不是用户输入，拼 SQL 这件事本身就不该开口子 */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9_]{1,64}$");

    private final JdbcTemplate jdbc;

    public BacklogStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 含该表的库（系统库除外）。顺序稳定，便于对比两次读数 */
    public List<String> schemasWithTable() {
        // 两边都 LOWER：H2 默认把非引号标识符存成大写，而 MySQL 按 lower_case_table_names
        // 可能是小写——只按一种写法查，换库布局档就会"发现 0 个库"，
        // 于是整块积压读数安静地消失（那正是本类要防的那类错）。
        return jdbc.queryForList(
                "SELECT DISTINCT table_schema FROM information_schema.tables "
                        + "WHERE LOWER(table_name) = LOWER(?) "
                        + "AND LOWER(table_schema) NOT IN ('information_schema','performance_schema','mysql','sys') "
                        + "ORDER BY 1", String.class, TABLE);
    }

    public List<SchemaBacklog> backlog() {
        List<SchemaBacklog> out = new ArrayList<>();
        for (String schema : schemasWithTable()) {
            out.add(read(schema));
        }
        return out;
    }

    /** 单个库：任何失败都只影响这个库那一行，且带着原因出现（少一行等于静默） */
    private SchemaBacklog read(String schema) {
        try {
            Map<String, Long> counts = statusCounts(schema);
            long pending = counts.getOrDefault("PENDING", 0L) + counts.getOrDefault("SENT", 0L);
            return new SchemaBacklog(schema, counts, pending,
                    pending > 0 ? earliestRetryAt(schema) : null,
                    counts.getOrDefault("FAILED", 0L) > 0 ? deadLetters(schema) : List.of(),
                    null);
        } catch (DataAccessException | IllegalArgumentException e) {
            log.warn("[ops] 读 {}.local_message 失败: {}", schema, e.toString());
            return new SchemaBacklog(schema, Map.of(), SchemaBacklog.UNKNOWN, null, List.of(),
                    e.getClass().getSimpleName() + ": " + root(e.getMessage()));
        }
    }

    /** package-private 且可覆写：测试靠它注入"某个库读不到"这种情形 */
    Map<String, Long> statusCounts(String schema) {
        Map<String, Long> out = new LinkedHashMap<>();
        // 块体是必需的：`rs -> out.put(...)` 有返回值，query(...) 的重载会在
        // ResultSetExtractor 与 RowCallbackHandler 之间歧义
        jdbc.query("SELECT status, COUNT(*) AS n FROM " + qualified(schema) + " GROUP BY status",
                (java.sql.ResultSet rs) -> {
                    out.put(rs.getString(1), rs.getLong(2));
                });
        return out;
    }

    LocalDateTime earliestRetryAt(String schema) {
        List<LocalDateTime> found = jdbc.queryForList(
                "SELECT MIN(next_retry_time) FROM " + qualified(schema)
                        + " WHERE status IN ('PENDING','SENT')", LocalDateTime.class);
        return found.isEmpty() ? null : found.get(0);
    }

    List<String> deadLetters(String schema) {
        return jdbc.query("SELECT topic, biz_key FROM " + qualified(schema)
                        + " WHERE status = 'FAILED' ORDER BY id LIMIT " + DEAD_LETTER_SAMPLE,
                (rs, i) -> rs.getString(1) + " / " + rs.getString(2));
    }

    private static String qualified(String schema) {
        if (!IDENTIFIER.matcher(schema == null ? "" : schema).matches()) {
            throw new IllegalArgumentException("非法的库名: " + schema);
        }
        return "`" + schema + "`." + TABLE;
    }

    private static String root(String message) {
        if (message == null) {
            return "unknown";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
