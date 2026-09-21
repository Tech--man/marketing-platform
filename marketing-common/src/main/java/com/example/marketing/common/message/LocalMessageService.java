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
     * 登记消息（业务事务内调用）。biz_key 唯一索引，重复登记直接忽略。
     */
    public void recordIfAbsent(String topic, String tag, String bizKey, String payload) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO " + TABLE + " (topic, tag, biz_key, payload, status, retry_count, next_retry_time)"
                        + " VALUES (?, ?, ?, ?, ?, 0, ?)",
                topic, tag, bizKey, payload, STATUS_PENDING, Timestamp.valueOf(LocalDateTime.now()));
    }

    /**
     * 发送并流转状态。发送异常不抛出（由定时器补偿），返回是否已发出。
     */
    public boolean publish(String bizKey) {
        Map<String, Object> row = load(bizKey);
        if (row == null || !List.of(STATUS_PENDING, STATUS_SENT).contains((String) row.get("status"))) {
            return false;
        }
        String topic = (String) row.get("topic");
        String tag = (String) row.get("tag");
        String payload = (String) row.get("payload");
        try {
            if (eventPublisher.publish(topic, tag, bizKey, payload)) {
                jdbcTemplate.update("UPDATE " + TABLE
                                + " SET status = ?, next_retry_time = ? WHERE biz_key = ? AND status IN (?, ?)",
                        STATUS_SENT, plusSeconds(120), bizKey, STATUS_PENDING, STATUS_SENT);
                return true;
            }
            scheduleRetry(bizKey, toInt(row.get("retry_count")));
        } catch (Exception e) {
            log.warn("[local-message] 发送失败 bizKey={}, 等待补偿: {}", bizKey, e.getMessage());
            scheduleRetry(bizKey, toInt(row.get("retry_count")));
        }
        return false;
    }

    /**
     * 消费端业务落库成功后确认。未命中说明重复消费或乱序，忽略即可。
     */
    public void confirm(String bizKey) {
        int updated = jdbcTemplate.update("UPDATE " + TABLE + " SET status = ? WHERE biz_key = ? AND status IN (?, ?)",
                STATUS_CONFIRMED, bizKey, STATUS_PENDING, STATUS_SENT);
        if (updated == 0) {
            log.debug("[local-message] confirm 未命中（重复消费）, bizKey={}", bizKey);
        }
    }

    /**
     * 扫描到期的未完成消息，供定时器调用。返回处理条数。
     */
    public int retryPending(int limit) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT biz_key FROM " + TABLE
                        + " WHERE status IN (?, ?) AND next_retry_time <= ? AND retry_count < ? ORDER BY id LIMIT ?",
                STATUS_PENDING, STATUS_SENT, Timestamp.valueOf(LocalDateTime.now()), MAX_RETRY, limit);
        int count = 0;
        for (Map<String, Object> row : rows) {
            publish((String) row.get("biz_key"));
            count++;
        }
        if (count > 0) {
            log.info("[local-message] 本轮补偿扫描 {} 条消息", count);
        }
        return count;
    }

    private void scheduleRetry(String bizKey, int currentRetry) {
        int retry = currentRetry + 1;
        long backoff = Math.min(BACKOFF_BASE_SECONDS * (1L << Math.min(retry, 6)), 300);
        String status = retry >= MAX_RETRY ? STATUS_FAILED : STATUS_PENDING;
        jdbcTemplate.update("UPDATE " + TABLE + " SET status = ?, retry_count = ?, next_retry_time = ? WHERE biz_key = ?",
                status, retry, plusSeconds(backoff), bizKey);
        if (STATUS_FAILED.equals(status)) {
            // 进入死信人工处理通道：生产环境应告警 + 转死信队列
            log.error("[local-message] 消息超过最大重试次数转 FAILED, bizKey={}", bizKey);
        }
    }

    private Map<String, Object> load(String bizKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT topic, tag, payload, status, retry_count FROM " + TABLE + " WHERE biz_key = ?", bizKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Timestamp plusSeconds(long seconds) {
        return Timestamp.valueOf(LocalDateTime.now().plusSeconds(seconds));
    }

    private int toInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
