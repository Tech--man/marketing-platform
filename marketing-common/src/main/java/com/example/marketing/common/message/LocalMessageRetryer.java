package com.example.marketing.common.message;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 本地消息表补偿定时器：周期扫描"已登记未确认"的消息重发。
 *
 * <p>生产环境建议替换为 XXL-Job 分片任务（扩展点），避免多实例重复扫描；
 * 本实现依赖 biz_key 幂等 + 消费端去重，多实例并发扫描也是安全的。</p>
 */
@Slf4j
public class LocalMessageRetryer {

    private final LocalMessageService localMessageService;

    public LocalMessageRetryer(LocalMessageService localMessageService) {
        this.localMessageService = localMessageService;
    }

    @Scheduled(fixedDelayString = "${marketing.message.retry-interval-ms:10000}")
    public void retry() {
        try {
            localMessageService.retryPending(200);
        } catch (Exception e) {
            log.error("[local-message] 补偿任务异常", e);
        }
    }
}
