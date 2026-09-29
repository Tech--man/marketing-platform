package com.example.marketing.account.service;

import com.example.marketing.common.schedule.RedisLeaseLock;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 会话保留期清理（2026-09-29 审查第四批）。
 *
 * <p>正确性一直是"用到了才判"（refresh 校验时查 revoked_at/refresh_expire_at），
 * 但行本身从不删除：每次登录插一行、refresh 只改不删，30 天窗口外加已吊销的历史行
 * 让 consumer_session 无界增长。本任务低频（默认 12h 一轮）删除 refresh 寿命已过
 * 且超过保留期（默认 7 天，给事后取证留窗口）的行——判定口径与 refresh() 相同，
 * 删掉的行不可能再通过任何校验，清理对正确性零影响。</p>
 *
 * <p>自起 daemon 线程而非 @Scheduled：account 独立进程没有 @EnableScheduling
 * （与 ⑤ 轮询器 / 审计 drain 同一理由——漏加开关的表现是"清理永远不跑"）。
 * 多副本（standalone 聚合 + FULL 独立进程不可能同跑，但 LITE 双副本实测过）靠
 * RedisLeaseLock 收敛到单实例执行；delete 本身幂等，锁只是防重复扫。</p>
 */
@Slf4j
@Component
public class ConsumerSessionCleanup {

    private final JdbcTemplate jdbcTemplate;
    private final RedisLeaseLock leaseLock;
    private final MeterRegistry meters;
    private final int retentionDays;
    private final long intervalHours;
    private volatile ScheduledExecutorService scheduler;

    public ConsumerSessionCleanup(JdbcTemplate jdbcTemplate, RedisLeaseLock leaseLock,
                                  MeterRegistry meters,
                                  @Value("${marketing.account.session-retention-days:7}") int retentionDays,
                                  @Value("${marketing.account.session-cleanup-hours:12}") long intervalHours) {
        this.jdbcTemplate = jdbcTemplate;
        this.leaseLock = leaseLock;
        this.meters = meters;
        this.retentionDays = retentionDays;
        this.intervalHours = intervalHours;
    }

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mkt-session-cleanup");
            t.setDaemon(true);
            return t;
        });
        // 首轮延迟：别跟启动抢 DB；此后 fixedDelay，上一轮跑完才计时
        scheduler.scheduleWithFixedDelay(this::runOnce,
                5, intervalHours, TimeUnit.HOURS);
    }

    @PreDestroy
    public void stop() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
    }

    void runOnce() {
        leaseLock.runExclusive("consumer-session-cleanup",
                java.time.Duration.ofHours(intervalHours), this::purgeExpired);
    }

    /** 删除 refresh 寿命已过且超出保留期的会话行。走 idx_refresh_expire 范围扫 */
    void purgeExpired() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            int deleted = jdbcTemplate.update(
                    "DELETE FROM consumer_session WHERE refresh_expire_at < ?", cutoff);
            if (deleted > 0) {
                meters.counter("marketing.account.session.cleanup.deleted").increment(deleted);
                log.info("[account] 会话清理：删除 refresh 寿命早于 {} 的 {} 行", cutoff, deleted);
            }
        } catch (RuntimeException e) {
            meters.counter("marketing.account.session.cleanup.error").increment();
            log.warn("[account] 会话清理本轮失败（下轮再来）: {}", e.toString());
        }
    }
}
