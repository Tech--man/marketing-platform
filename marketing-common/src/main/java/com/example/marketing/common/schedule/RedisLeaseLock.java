package com.example.marketing.common.schedule;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

/**
 * 周期任务去重锁：多实例部署时让同一个调度周期只有一个实例真正执行。
 *
 * <p>语义要点：<b>拿到就故意不释放</b>，只设一个等于调度周期的 TTL，让它自然到期。
 * 若像常规分布式锁那样在 finally 里 release，两个实例只是不会"同时"执行，
 * 却仍会各自在自己的 tick 上各执行一遍 —— 去重目的完全落空。留到周期结束自然过期，
 * 才等价于"这一轮已经有人认领了"。</p>
 *
 * <p>为什么仍需要它：补偿/回补/过期扫描的正确性本就由 biz_key 幂等与状态 CAS 兜底，
 * 重复执行不会算错账；但不加锁时 N 个实例会把同一批未确认消息重投 N 份，
 * 恰在洪峰期自我放大流量。彻底的分片调度（XXL-Job / SchedulerX）仍是扩展点。</p>
 */
@Slf4j
public class RedisLeaseLock {

    private static final String KEY_PREFIX = "mkt:job:";

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final String owner = UUID.randomUUID().toString();

    public RedisLeaseLock(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @param task     任务名（决定锁 key）
     * @param interval 调度周期：key 的 TTL，等于周期长度即"本周期只执行一次"
     */
    public void runExclusive(String task, Duration interval, Runnable body) {
        String key = KEY_PREFIX + task;
        Boolean acquired;
        try {
            acquired = redisTemplate.opsForValue().setIfAbsent(key, owner, interval);
        } catch (Exception e) {
            // Redis 抖动时宁可执行：漏一轮补偿比重复执行更伤，业务侧有幂等兜底
            log.warn("[job] 去重键读取异常，本轮照常执行 task={}: {}", task, e.getMessage());
            acquired = true;
        }
        if (!Boolean.TRUE.equals(acquired)) {
            meterRegistry.counter("mkt.job.dedup_skipped", "task", task).increment();
            log.debug("[job] 本周期已被其他实例认领，跳过 task={}", task);
            return;
        }
        body.run();
    }
}
