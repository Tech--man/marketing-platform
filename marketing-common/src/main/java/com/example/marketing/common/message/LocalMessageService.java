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
    /**
     * 消费侧死信阈值（P2，2026-09-30 第二轮复审）：SENT 行每次兜底重发计数 +1，
     * 达到该值仍无 confirm 即转 FAILED。约 30×120s ≈ 1 小时——比发送失败的
     * MAX_RETRY 宽三倍，慢消费（积压但会 confirm）不会被误杀。
     */
    private static final int SENT_MAX_RETRY = 30;
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
        // W3.9（2026-09-30 第二轮复审）：长度预算前置——payload 是 TEXT(64KB)、tag/biz_key
        // 是 VARCHAR(64)/(128)。INSERT IGNORE 会把超长截断降级成 warning 静默入库，
        // 消费端 JsonUtils.parse 永远失败，叠加重发就是无限毒循环；显式拒绝让调用方
        // 在业务侧拿到失败（预扣回滚路径会接住），而不是事后死信排查。
        if (payload != null && payload.length() > 60_000) {
            throw new IllegalArgumentException("payload 超过 60KB 预算（TEXT 64KB 减安全余量）: "
                    + payload.length() + " 字符");
        }
        if (tag != null && tag.length() > 64) {
            throw new IllegalArgumentException("tag 超 VARCHAR(64) 列宽: " + tag.length());
        }
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
                // P2（2026-09-30 第二轮复审）：发送成功也推进 retry_count——它是"投递
                // 尝试次数"。原实现只有发送失败才计数，"发出去了但消费端永远消费不
                // 成功"的毒消息（载荷坏/消费端持续抛错）会以 120s 一轮无限重发，
                // 永不进 FAILED、FAILED gauge 恒 0。正常消息很快 confirm，计数无感。
                jdbcTemplate.update("UPDATE " + TABLE
                                + " SET status = ?, next_retry_time = ?, retry_count = retry_count + 1 "
                                + "WHERE topic = ? AND biz_key = ? AND status IN (?, ?)",
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
        // P2（2026-09-30 第二轮复审）：消费侧死信封顶。SENT 行每次兜底重发计数 +1
        // （见 publish），到 SENT_MAX_RETRY 仍无 confirm = 消费端持续失败（毒载荷/
        // 消费端缺陷）——转 FAILED 进入死信通道，终止 120s 一轮的无限重发。
        // 上限刻意比发送失败的 MAX_RETRY 宽：正常慢消费（积压但会 confirm）不该被误杀。
        int dead = jdbcTemplate.update("UPDATE " + TABLE + " SET status = ? WHERE status = ? AND retry_count >= ?",
                STATUS_FAILED, STATUS_SENT, SENT_MAX_RETRY);
        if (dead > 0) {
            log.error("[local-message] {} 条 SENT 消息超过 {} 轮仍未确认，转消费侧死信 FAILED", dead, SENT_MAX_RETRY);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT topic, biz_key FROM " + TABLE
                        + " WHERE ((status = ? AND retry_count < ?) OR (status = ? AND retry_count < ?))"
                        + " AND next_retry_time <= ? ORDER BY id LIMIT ?",
                STATUS_PENDING, MAX_RETRY, STATUS_SENT, SENT_MAX_RETRY,
                Timestamp.valueOf(LocalDateTime.now()), limit);
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
     * <p>W3.3（2026-09-30 第二轮复审）：批删升级为循环删空——原实现每类每轮只删
     * 一批 1000 行（每小时一次），持续确认速率高于 1000/h 时表仍无界增长。
     * 现按批循环直到删空，配单轮总量上限（50 万）防极端积压把归档轮跑成小时级。
     * W3.4：幂等记录保留期独立成参（marketing.idempotent.retention-days），与消息域
     * 保留期解耦——幂等键语义不该被改消息配置的人顺手改掉。</p>
     */
    public int purgeTerminated(int confirmedRetentionDays, int idempotentRetentionDays) {
        int total = 0;
        final int batch = 1000;
        final int hardCap = 500_000;
        LocalDateTime confirmedCutoff = LocalDateTime.now().minusDays(confirmedRetentionDays);
        LocalDateTime idempotentCutoff = LocalDateTime.now().minusDays(idempotentRetentionDays);
        LocalDateTime failedCutoff = LocalDateTime.now().minusDays(Math.max(idempotentRetentionDays, 90));
        // H2 的 MySQL 模式不认 DELETE ... LIMIT（MySQL 方言），换等价的子查询写法——
        // 主键序子查询取前 N 条再 IN，两边语义一致
        total += deleteInBatches("local_message", "status = '" + STATUS_CONFIRMED + "'",
                confirmedCutoff, batch, hardCap);
        total += deleteInBatches("idempotent_record", "status = 'SUCCESS'",
                idempotentCutoff, batch, hardCap - total);
        total += deleteInBatches("idempotent_record", "status = 'FAILED'",
                failedCutoff, batch, hardCap - total);
        return total;
    }

    /** 兼容旧签名（测试/内部调用）：两表同保留期 */
    public int purgeTerminated(int retentionDays) {
        return purgeTerminated(retentionDays, retentionDays);
    }

    /**
     * 按主键序分批循环删除，直到条件不再命中或触达本轮上限。每批一次独立
     * autocommit，避免长事务锁表；取不满一批 = 没有更多匹配行，提前收束。
     */
    private int deleteInBatches(String table, String statusCondition, LocalDateTime cutoff,
                                int batch, int remainingCap) {
        int total = 0;
        while (total < remainingCap) {
            int size = Math.min(batch, remainingCap - total);
            int deleted = jdbcTemplate.update(
                    "DELETE FROM " + table + " WHERE " + statusCondition + " AND create_time < ? "
                            + "AND id IN (SELECT id FROM (SELECT id FROM " + table
                            + " WHERE " + statusCondition + " AND create_time < ? ORDER BY id LIMIT " + size
                            + ") t)",
                    Timestamp.valueOf(cutoff), Timestamp.valueOf(cutoff));
            total += deleted;
            if (deleted < size) {
                break;
            }
        }
        return total;
    }

    private void scheduleRetry(String topic, String bizKey, int currentRetry) {
        int retry = currentRetry + 1;
        long backoff = Math.min(BACKOFF_BASE_SECONDS * (1L << Math.min(retry, 6)), 300);
        String status = retry >= MAX_RETRY ? STATUS_FAILED : STATUS_PENDING;
        // P2（2026-09-30 第二轮复审）：WHERE 带状态守卫——retryer 的 publish 在 load 之后
        // 抛 DB 异常进入 catch 的同一毫秒，消费端可能恰好 confirm；无守卫的 UPDATE 会把
        // CONFIRMED 回退成 PENDING/FAILED（假死信污染告警 + 已闭环消息被重投）。
        jdbcTemplate.update("UPDATE " + TABLE
                        + " SET status = ?, retry_count = ?, next_retry_time = ? "
                        + "WHERE topic = ? AND biz_key = ? AND status IN (?, ?)",
                status, retry, plusSeconds(backoff), topic, bizKey, STATUS_PENDING, STATUS_SENT);
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
