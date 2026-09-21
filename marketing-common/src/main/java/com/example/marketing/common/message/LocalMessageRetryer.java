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

    public LocalMessageRetryer(LocalMessageService localMessageService, RedisLeaseLock leaseLock) {
        this.localMessageService = localMessageService;
        this.leaseLock = leaseLock;
    }

    @Scheduled(fixedDelayString = "${marketing.message.retry-interval-ms:10000}")
    public void retry() {
        leaseLock.runExclusive("local-message-retry", INTERVAL, () -> {
            try {
                localMessageService.retryPending(200);
            } catch (Exception e) {
                log.error("[local-message] 补偿任务异常", e);
            }
        });
    }
}
