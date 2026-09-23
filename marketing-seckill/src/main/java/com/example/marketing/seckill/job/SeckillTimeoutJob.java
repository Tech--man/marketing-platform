package com.example.marketing.seckill.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.marketing.common.schedule.RedisLeaseLock;
import com.example.marketing.seckill.config.SeckillProperties;
import com.example.marketing.seckill.config.SeckillRuntimeConfig;
import com.example.marketing.seckill.domain.SeckillOrderStatus;
import com.example.marketing.seckill.infrastructure.entity.SeckillActivityEntity;
import com.example.marketing.seckill.infrastructure.entity.SeckillOrderEntity;
import com.example.marketing.seckill.infrastructure.mapper.SeckillActivityMapper;
import com.example.marketing.seckill.infrastructure.mapper.SeckillOrderMapper;
import com.example.marketing.seckill.service.SeckillOrderService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 超时未支付回补 Job（代替 XXL-Job 的 demo 实现，生产应换分布式调度防多实例重复跑；
 * 即便多实例并发，cancelTimeout 的条件更新也保证只有一边能取消成功）。
 *
 * <p>每 5 秒扫描创建超过支付时限仍 CREATED 的订单 → 取消 → 回补 Redis 分桶 →
 * 移除防重购标记 → DB 已售数回减。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillTimeoutJob {

    private final SeckillOrderMapper orderMapper;
    private final SeckillActivityMapper activityMapper;
    private final SeckillOrderService orderService;
    private final SeckillProperties properties;
    /** 支付超时走在线可调口径；buckets 仍读 properties（它是列值的地基，不在线化） */
    private final SeckillRuntimeConfig runtime;
    private final MeterRegistry meterRegistry;
    private final RedisLeaseLock leaseLock;

    @Scheduled(fixedDelayString = "${marketing.seckill.timeout-scan-interval-ms:5000}")
    public void cancelTimeoutOrders() {
        leaseLock.runExclusive("seckill-timeout", Duration.ofSeconds(5), this::doCancelTimeoutOrders);
    }

    private void doCancelTimeoutOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(runtime.payTimeoutSeconds());
        List<SeckillOrderEntity> timeoutOrders = orderMapper.selectList(
                new LambdaQueryWrapper<SeckillOrderEntity>()
                        .eq(SeckillOrderEntity::getStatus, SeckillOrderStatus.CREATED.name())
                        .lt(SeckillOrderEntity::getCreateTime, deadline)
                        .last("LIMIT 200"));
        if (timeoutOrders.isEmpty()) {
            return;
        }
        Map<String, Integer> bucketCache = new HashMap<>();
        for (SeckillOrderEntity order : timeoutOrders) {
            int buckets = bucketCache.computeIfAbsent(order.getActivityNo(), this::resolveBuckets);
            if (orderService.cancelTimeout(order, buckets)) {
                Counter.builder("seckill.order.timeout_cancelled").register(meterRegistry).increment();
                log.info("[seckill] 超时取消并回补 orderNo={}, activityNo={}", order.getOrderNo(), order.getActivityNo());
            }
        }
    }

    private int resolveBuckets(String activityNo) {
        SeckillActivityEntity activity = activityMapper.selectOne(
                new LambdaQueryWrapper<SeckillActivityEntity>()
                        .eq(SeckillActivityEntity::getActivityNo, activityNo));
        return activity == null || activity.getBuckets() == null
                ? properties.getBuckets() : activity.getBuckets();
    }
}
