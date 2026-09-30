package com.example.marketing.common.message;

import lombok.extern.slf4j.Slf4j;
import com.example.marketing.common.schedule.RedisLeaseLock;
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

    /** 与 retry-interval-ms 同长：一个周期只让一个实例认领 */
    private static final Duration INTERVAL = Duration.ofSeconds(10);

    private final LocalMessageService localMessageService;
    private final RedisLeaseLock leaseLock;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public LocalMessageRetryer(LocalMessageService localMessageService, RedisLeaseLock leaseLock,
                               io.micrometer.core.instrument.MeterRegistry meters) {
        this.localMessageService = localMessageService;
        this.leaseLock = leaseLock;
        this.meters = meters;
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
        leaseLock.runExclusive("local-message-retry", INTERVAL, () -> {
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
                    // 终态归档（2026-09-29）：CONFIRMED/SUCCESS 超保留期删、FAILED 留 90 天。
                    // 与重发同锁同线程：归档不该与投递抢 DB，也不值得再配一把锁
                    int purged = localMessageService.purgeTerminated(
                            Integer.parseInt(System.getProperty(
                                    "marketing.message.terminal-retention-days", "30")));
                    if (purged > 0) {
                        meters.counter("marketing.message.purged").increment(purged);
                        log.info("[local-message] 终态归档删除 {} 行（CONFIRMED/SUCCESS 超 30 天）", purged);
                    }
                }
            } catch (Exception e) {
                log.error("[local-message] 补偿任务异常", e);
            }
        });
    }
}
