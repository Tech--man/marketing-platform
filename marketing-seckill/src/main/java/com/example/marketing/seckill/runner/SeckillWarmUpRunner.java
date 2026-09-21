package com.example.marketing.seckill.runner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.marketing.seckill.config.SeckillProperties;
import com.example.marketing.seckill.infrastructure.entity.SeckillActivityEntity;
import com.example.marketing.seckill.infrastructure.mapper.SeckillActivityMapper;
import com.example.marketing.seckill.service.SeckillStockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 启动预热：把 ONLINE 且在时间窗口内的秒杀活动库存按分桶写入 Redis。
 *
 * <p>SETNX 语义保证重启/多实例不会重置已售进度；Redis 不可用时仅告警，
 * 抢购请求会以"未预热"错误快速失败而不是拖挂服务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillWarmUpRunner implements ApplicationRunner {

    private final SeckillActivityMapper activityMapper;
    private final SeckillStockService stockService;
    private final SeckillProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        List<SeckillActivityEntity> activities = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivityEntity>()
                        .eq(SeckillActivityEntity::getStatus, "ONLINE")
                        .and(w -> w.isNull(SeckillActivityEntity::getEndTime)
                                .or().gt(SeckillActivityEntity::getEndTime, LocalDateTime.now())));
        for (SeckillActivityEntity activity : activities) {
            try {
                int buckets = activity.getBuckets() == null ? properties.getBuckets() : activity.getBuckets();
                int remain = activity.getTotalStock() - (activity.getSoldStock() == null ? 0 : activity.getSoldStock());
                stockService.warmUp(activity, stockService.allocateBuckets(Math.max(remain, 0), buckets));
            } catch (Exception e) {
                log.warn("[seckill] 活动 {} 预热失败（Redis 不可用？）: {}", activity.getActivityNo(), e.getMessage());
            }
        }
    }
}
