package com.example.marketing.common.message;

import lombok.extern.slf4j.Slf4j;
import com.example.marketing.common.schedule.RedisLeaseLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

/**
 * 本地消息表补偿定时器：周期扫描"已登记未确认"的消息重发。
 *
 * <p>多实例下靠租约锁收敛到单实例执行：正确性本就由 biz_key 幂等 + 消费端去重保证，
 * 但不加锁时 N 个实例会把同一批消息重投 N 份，恰在洪峰期自我放大流量。
 * 彻底的分片调度（XXL-Job / SchedulerX）仍是扩展点。</p>
 */
@Slf4j
public class LocalMessageRetryer {

    /**
     * 租约时长与 retry-interval-ms 同源注入（W3.5，2026-09-30 第二轮复审）：原实现
     * 硬编码 10s——运维把间隔调大到 30s 降噪时锁先过期，每轮所有实例全部认领，
     * 多实例重复扫描投递静默回到无锁状态。单轮处理超过锁时长的插入窗口属
     * at-least-once 已声明的边界。
     */
    private final Duration lockLease;

    private final LocalMessageService localMessageService;
    private final RedisLeaseLock leaseLock;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    /**
     * 幂等记录归档保留期（W3.4）：与消息域分离——幂等键的对外契约是"永久有效"，
     * 实际按本配置的窗口收窄，业务表唯一索引（user_coupon.request_id 等）才是最后
     * 一道闸。挂在消息域配置下会让改消息保留期的人无意中改掉幂等语义。
     */
    private final int idempotentRetentionDays;

    public LocalMessageRetryer(LocalMessageService localMessageService, RedisLeaseLock leaseLock,
                               io.micrometer.core.instrument.MeterRegistry meters,
                               @Value("${marketing.message.retry-interval-ms:10000}") long retryIntervalMs,
                               @Value("${marketing.idempotent.retention-days:30}") int idempotentRetentionDays) {
        this.localMessageService = localMessageService;
        this.leaseLock = leaseLock;
        this.meters = meters;
        this.lockLease = Duration.ofMillis(Math.max(retryIntervalMs, 10_000L));
        this.idempotentRetentionDays = idempotentRetentionDays;
        // gauge 持有的 service 是容器强引用的 bean，不会被弱引用回收；
        // 每次抓取一次 COUNT 查询，量级与 ④ 的抓取节奏相当
        io.micrometer.core.instrument.Gauge
                .builder("marketing.message.failed", localMessageService, s -> s.countFailed())
                .register(meters);
    }

    /** 归档节流：retry 每 10s 一轮，归档不需要这个频率——每 360 轮（约 1h）跑一次 */
    private java.util.concurrent.atomic.AtomicLong cycles = new java.util.concurrent.atomic.AtomicLong();

    @Scheduled(fixedDelayString = "${marketing.message.retry-interval-ms:10000}")
    public void retry() {
        leaseLock.runExclusive("local-message-retry", lockLease, () -> {
            try {
                localMessageService.retryPending(200);
                // 对账兜底信号（2026-09-29 审查）：FAILED 死信出现即告警——
                // 它是终态，不驱赶就永远停在表里；驱动通道是管理端的 messages/redrive
                int failed = localMessageService.countFailed();
                if (failed > 0) {
                    log.error("[local-message] FAILED 死信 {} 条待处理（管理端 messages/redrive 可重驱动）",
                            failed);
                }
                if (cycles.incrementAndGet() % 360 == 0) {
                    // 终态归档（2026-09-29）：CONFIRMED 消息超消息域保留期删、SUCCESS
                    // 幂等记录超幂等域保留期删（W3.4 起两个配置分离）、FAILED 留 90 天。
                    // 与重发同锁同线程：归档不该与投递抢 DB，也不值得再配一把锁
                    int purged = localMessageService.purgeTerminated(
                            Integer.parseInt(System.getProperty(
                                    "marketing.message.terminal-retention-days", "30")),
                            idempotentRetentionDays);
                    if (purged > 0) {
                        meters.counter("marketing.message.purged").increment(purged);
                        log.info("[local-message] 终态归档删除 {} 行（消息 CONFIRMED 与幂等 SUCCESS 各按各的保留期）",
                                purged);
                    }
                }
            } catch (Exception e) {
                log.error("[local-message] 补偿任务异常", e);
            }
        });
    }
}
