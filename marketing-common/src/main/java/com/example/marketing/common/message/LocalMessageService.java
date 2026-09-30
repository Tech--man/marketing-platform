package com.example.marketing.common.message;

import com.example.marketing.common.mq.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 本地消息表：保证"业务动作"与"MQ 投递"的最终一致。
 *
 * <p>流程：业务事务内 {@link #recordIfAbsent} 写入 PENDING → 尝试发送置为 SENT →
 * 消费端处理成功后 {@link #confirm} 置为 CONFIRMED；{@link LocalMessageRetryer}
 * 定时扫描超时未确认的消息重发（MQ 至少一次，消费端必须幂等）。</p>
 *
 * <p>依赖表 local_message，DDL 见 docker/mysql/init.sql。</p>
 */
@Slf4j
public class LocalMessageService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_FAILED = "FAILED";

    private static final String TABLE = "local_message";
    private static final int MAX_RETRY = 10;
    /** 退避基数（秒）：next_retry = now + base * 2^retry，封顶 5 分钟 */
    private static final long BACKOFF_BASE_SECONDS = 5;

    private final JdbcTemplate jdbcTemplate;
    private final EventPublisher eventPublisher;

    public LocalMessageService(JdbcTemplate jdbcTemplate, EventPublisher eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 登记消息（业务事务内调用）。
     *
     * <p>唯一键是 {@code (topic, biz_key)}：bizKey 由调用方决定，跨 topic 撞同名是可能的
     * （{@code /api/coupon/grant} 的 requestId 外部可控，传 {@code seckill:x} 就会撞秒杀的键形）。
     * 返回 false 表示这条已登记过，调用方据此区分"新建"与"重复"，不能静默。</p>
     */
    public boolean recordIfAbsent(String topic, String tag, String bizKey, String payload) {
        return jdbcTemplate.update(
                "INSERT IGNORE INTO " + TABLE + " (topic, tag, biz_key, payload, status, retry_count, next_retry_time)"
                        + " VALUES (?, ?, ?, ?, ?, 0, ?)",
                topic, tag, bizKey, payload, STATUS_PENDING, Timestamp.valueOf(LocalDateTime.now())) > 0;
    }

    /**
     * 发送并流转状态。发送异常不抛出（由定时器补偿），返回是否已发出。
     *
     * <p>必须带 topic 定位：只有 biz_key 时会命中别的 topic 的同名行，
     * 结果是"这条没发出去、那条被顺手改成了 SENT"。</p>
     */
    public boolean publish(String topic, String bizKey) {
        Map<String, Object> row = load(topic, bizKey);
        if (row == null || !List.of(STATUS_PENDING, STATUS_SENT).contains((String) row.get("status"))) {
            return false;
        }
        String tag = (String) row.get("tag");
        String payload = (String) row.get("payload");
        try {
            if (eventPublisher.publish(topic, tag, bizKey, payload)) {
                jdbcTemplate.update("UPDATE " + TABLE
                                + " SET status = ?, next_retry_time = ? WHERE topic = ? AND biz_key = ? AND status IN (?, ?)",
                        STATUS_SENT, plusSeconds(120), topic, bizKey, STATUS_PENDING, STATUS_SENT);
                return true;
            }
            scheduleRetry(topic, bizKey, toInt(row.get("retry_count")));
        } catch (Exception e) {
            log.warn("[local-message] 发送失败 topic={}, bizKey={}, 等待补偿: {}", topic, bizKey, e.getMessage());
            scheduleRetry(topic, bizKey, toInt(row.get("retry_count")));
        }
        return false;
    }

    /**
     * 消费端业务落库成功后确认。未命中说明重复消费或乱序，忽略即可。
     */
    public void confirm(String topic, String bizKey) {
        int updated = jdbcTemplate.update(
                "UPDATE " + TABLE + " SET status = ? WHERE topic = ? AND biz_key = ? AND status IN (?, ?)",
                STATUS_CONFIRMED, topic, bizKey, STATUS_PENDING, STATUS_SENT);
        if (updated == 0) {
            log.debug("[local-message] confirm 未命中（重复消费）, topic={}, bizKey={}", topic, bizKey);
        }
    }

    /**
     * 扫描到期的未完成消息，供定时器调用。返回处理条数。
     */
    public int retryPending(int limit) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT topic, biz_key FROM " + TABLE
                        + " WHERE status IN (?, ?) AND next_retry_time <= ? AND retry_count < ? ORDER BY id LIMIT ?",
                STATUS_PENDING, STATUS_SENT, Timestamp.valueOf(LocalDateTime.now()), MAX_RETRY, limit);
        int count = 0;
        for (Map<String, Object> row : rows) {
            publish((String) row.get("topic"), (String) row.get("biz_key"));
            count++;
        }
        if (count > 0) {
            log.info("[local-message] 本轮补偿扫描 {} 条消息", count);
        }
        return count;
    }

    /**
     * FAILED 死信计数（2026-09-29 审查，对账兜底的可观测面）。
     * FAILED 是终态：一旦出现就永远停在表里，>0 即说明有消息需要人工处理或
     * {@link #redriveFailed} 重驱动——原实现只留一条 log.error，没有任何地方能再看见它。
     */
    public int countFailed() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE status = ?", Integer.class, STATUS_FAILED);
        return count == null ? 0 : count;
    }

    /**
     * 重驱动 FAILED 死信：置回 PENDING、清零重试计数，由补偿定时器重新投递。
     * 重投安全由消费端幂等保证（MQ 至少一次的既定前提）。返回重驱动的条数。
     * 不带 LIMIT：FAILED 是终态、量级是异常事件的人工处理量，全量重驱动才是本意
     * （半截重驱动会把"哪些驱过哪些没驱"变成新的对账问题）。
     */
    public int redriveFailed(String topic) {
        int driven = jdbcTemplate.update(
                "UPDATE " + TABLE + " SET status = ?, retry_count = 0, next_retry_time = ? "
                        + "WHERE topic = ? AND status = ?",
                STATUS_PENDING, Timestamp.valueOf(LocalDateTime.now()), topic, STATUS_FAILED);
        if (driven > 0) {
            log.warn("[local-message] 重驱动 FAILED 死信 {} 条 topic={}（由补偿定时器重新投递）", driven, topic);
        }
        return driven;
    }

    /**
     * 终态归档（2026-09-29 审查收口）：CONFIRMED 的消息与 SUCCESS 的幂等记录
     * 完成使命后仍永久留表（此前两表无界增长；FAILED 留 90 天给死信排查取证）。
     * 分批删除（每批 {@code 1000}）避免长事务锁表；返回本轮删除总数。
     */
    public int purgeTerminated(int confirmedRetentionDays) {
        int total = 0;
        LocalDateTime confirmedCutoff = LocalDateTime.now().minusDays(confirmedRetentionDays);
        LocalDateTime failedCutoff = LocalDateTime.now().minusDays(Math.max(confirmedRetentionDays, 90));
        // H2 的 MySQL 模式不认 DELETE ... LIMIT（MySQL 方言），换等价的子查询写法——
        // 两边都走主键序子查询取前 N 条再 IN，语义不变
        int deleted;
        deleted = jdbcTemplate.update(
                "DELETE FROM local_message WHERE status = ? AND create_time < ? "
                        + "AND id IN (SELECT id FROM local_message WHERE status = ? AND create_time < ? "
                        + "AND id IN (SELECT id FROM (SELECT id FROM local_message "
                        + "WHERE status = ? AND create_time < ? ORDER BY id LIMIT 1000) t))",
                STATUS_CONFIRMED, Timestamp.valueOf(confirmedCutoff),
                STATUS_CONFIRMED, Timestamp.valueOf(confirmedCutoff),
                STATUS_CONFIRMED, Timestamp.valueOf(confirmedCutoff));
        total += deleted;
        deleted = jdbcTemplate.update(
                "DELETE FROM idempotent_record WHERE status = 'SUCCESS' AND create_time < ? "
                        + "AND id IN (SELECT id FROM (SELECT id FROM idempotent_record "
                        + "WHERE status = 'SUCCESS' AND create_time < ? ORDER BY id LIMIT 1000) t)",
                Timestamp.valueOf(confirmedCutoff), Timestamp.valueOf(confirmedCutoff));
        total += deleted;
        deleted = jdbcTemplate.update(
                "DELETE FROM idempotent_record WHERE status = 'FAILED' AND create_time < ? "
                        + "AND id IN (SELECT id FROM (SELECT id FROM idempotent_record "
                        + "WHERE status = 'FAILED' AND create_time < ? ORDER BY id LIMIT 1000) t)",
                Timestamp.valueOf(failedCutoff), Timestamp.valueOf(failedCutoff));
        total += deleted;
        return total;
    }

    private void scheduleRetry(String topic, String bizKey, int currentRetry) {
        int retry = currentRetry + 1;
        long backoff = Math.min(BACKOFF_BASE_SECONDS * (1L << Math.min(retry, 6)), 300);
        String status = retry >= MAX_RETRY ? STATUS_FAILED : STATUS_PENDING;
        jdbcTemplate.update("UPDATE " + TABLE
                        + " SET status = ?, retry_count = ?, next_retry_time = ? WHERE topic = ? AND biz_key = ?",
                status, retry, plusSeconds(backoff), topic, bizKey);
        if (STATUS_FAILED.equals(status)) {
            // 进入死信人工处理通道：生产环境应告警 + 转死信队列
            log.error("[local-message] 消息超过最大重试次数转 FAILED, topic={}, bizKey={}", topic, bizKey);
        }
    }

    private Map<String, Object> load(String topic, String bizKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT topic, tag, payload, status, retry_count FROM " + TABLE
                        + " WHERE topic = ? AND biz_key = ?", topic, bizKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Timestamp plusSeconds(long seconds) {
        return Timestamp.valueOf(LocalDateTime.now().plusSeconds(seconds));
    }

    private int toInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
