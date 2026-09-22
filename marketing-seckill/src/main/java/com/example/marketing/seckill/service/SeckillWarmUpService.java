package com.example.marketing.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.cache.CacheReheater;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.seckill.config.SeckillProperties;
import com.example.marketing.seckill.infrastructure.entity.SeckillActivityEntity;
import com.example.marketing.seckill.infrastructure.mapper.SeckillActivityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀分桶预热的唯一入口：启动预热与运维重预热都走这里，口径只有一份。
 *
 * <p>SETNX 语义（{@code force=false}）保证重启/多实例不会把已售进度冲掉；
 * 覆盖语义（{@code force=true}）是给"运营抬完 total_stock"用的，等于重新开闸，
 * 因此对非 ONLINE 或已过结束时间的活动一律拒绝。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillWarmUpService implements CacheReheater {

    private static final String STATUS_ONLINE = "ONLINE";

    private final SeckillActivityMapper activityMapper;
    private final SeckillStockService stockService;
    private final SeckillProperties properties;

    /** 启动预热：对所有 ONLINE 且在时间窗口内的活动补建缺失的分桶 */
    public void warmAllOnline() {
        List<SeckillActivityEntity> activities = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivityEntity>()
                        .eq(SeckillActivityEntity::getStatus, STATUS_ONLINE)
                        .and(w -> w.isNull(SeckillActivityEntity::getEndTime)
                                .or().gt(SeckillActivityEntity::getEndTime, LocalDateTime.now())));
        for (SeckillActivityEntity activity : activities) {
            try {
                stockService.warmUp(activity, planFor(activity));
            } catch (Exception e) {
                log.warn("[seckill] 活动 {} 预热失败（Redis 不可用？）: {}", activity.getActivityNo(), e.getMessage());
            }
        }
        log.info("[seckill] 启动预热完成，ONLINE 活动数={}", activities.size());
    }

    @Override
    public String type() {
        return "seckill-stock";
    }

    @Override
    public CacheReheater.Result reheat(String activityNo, boolean force) {
        SeckillActivityEntity activity = requireReheatable(activityNo);
        List<Long> before = stockService.currentBucketStocks(activityNo, bucketsOf(activity));
        long beforeSum = before.stream().filter(v -> v >= 0).mapToLong(Long::longValue).sum();
        List<Integer> plan = planFor(activity);
        int touched = force
                ? stockService.resetBuckets(activityNo, plan)
                : stockService.warmUp(activity, plan);
        return new CacheReheater.Result(type(), activityNo, beforeSum,
                plan.stream().mapToLong(Integer::intValue).sum(),
                "total_stock - sold_stock 按 " + touched + " 桶分配");
    }

    /** 分桶配额：remain = total - sold，按 allocateBuckets 均摊（余数给前几个桶） */
    List<Integer> planFor(SeckillActivityEntity activity) {
        return stockService.allocateBuckets(
                remainOf(activity.getTotalStock(), activity.getSoldStock()), bucketsOf(activity));
    }

    static int remainOf(Integer totalStock, Integer soldStock) {
        int total = totalStock == null ? 0 : totalStock;
        int sold = soldStock == null ? 0 : soldStock;
        return Math.max(0, total - sold);
    }

    private int bucketsOf(SeckillActivityEntity activity) {
        return activity.getBuckets() == null ? properties.getBuckets() : activity.getBuckets();
    }

    private SeckillActivityEntity requireReheatable(String activityNo) {
        SeckillActivityEntity activity = activityMapper.selectOne(
                Wrappers.<SeckillActivityEntity>lambdaQuery()
                        .eq(SeckillActivityEntity::getActivityNo, activityNo));
        if (activity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "秒杀活动不存在: " + activityNo);
        }
        if (!STATUS_ONLINE.equals(activity.getStatus())) {
            throw BizException.of(ErrorCode.ACTIVITY_NOT_ONLINE, "仅 ONLINE 活动可重预热: " + activityNo);
        }
        if (activity.getEndTime() != null && activity.getEndTime().isBefore(LocalDateTime.now())) {
            throw BizException.of(ErrorCode.ACTIVITY_NOT_ONLINE, "活动已结束，拒绝重新开闸: " + activityNo);
        }
        return activity;
    }
}
