package com.example.marketing.admin.security;

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
 * 后台会话保留期清理（2026-09-29 审查第五批，与 consumer_session 的清理同款）。
 *
 * <p>admin_session 每次登录插一行，吊销/过期判定靠 Redis 键（TTL 自然消亡），
 * 行本身从不删除——无界增长。删除条件 {@code expire_at < now - 保留期}：admin 会话
 * 没有 refresh（accessTtl 上界 15 分钟），expire_at 过就是彻底死了，加保留期只为
 * 事后取证窗口。多 admin 副本（README 实测 2 副本）靠 RedisLeaseLock 收敛。</p>
 *
 * <p>admin 进程没有 @EnableScheduling（与审计 drain 同一理由），自起 daemon 线程。</p>
 */
@Slf4j
@Component
public class AdminSessionCleanup {

    private final JdbcTemplate jdbcTemplate;
    private final RedisLeaseLock leaseLock;
    private final MeterRegistry meters;
    private final int retentionDays;
    private final long intervalHours;
    private volatile ScheduledExecutorService scheduler;

    public AdminSessionCleanup(JdbcTemplate jdbcTemplate, RedisLeaseLock leaseLock,
                               MeterRegistry meters,
                               @Value("${marketing.admin.session-retention-days:7}") int retentionDays,
                               @Value("${marketing.admin.session-cleanup-hours:12}") long intervalHours) {
        this.jdbcTemplate = jdbcTemplate;
        this.leaseLock = leaseLock;
        this.meters = meters;
        this.retentionDays = retentionDays;
        this.intervalHours = intervalHours;
    }

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mkt-admin-session-cleanup");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::runOnce, 5, intervalHours, TimeUnit.HOURS);
    }

    @PreDestroy
    public void stop() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
    }

    void runOnce() {
        leaseLock.runExclusive("admin-session-cleanup",
                java.time.Duration.ofHours(intervalHours), this::purgeExpired);
    }

    void purgeExpired() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            int deleted = jdbcTemplate.update(
                    "DELETE FROM admin_session WHERE expire_at < ?", cutoff);
            if (deleted > 0) {
                meters.counter("marketing.admin.session.cleanup.deleted").increment(deleted);
                log.info("[admin] 会话清理：删除 expire_at 早于 {} 的 {} 行", cutoff, deleted);
            }
        } catch (RuntimeException e) {
            meters.counter("marketing.admin.session.cleanup.error").increment();
            log.warn("[admin] 会话清理本轮失败（下轮再来）: {}", e.toString());
        }
    }
}
