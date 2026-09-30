package com.example.marketing.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.cache.CacheConsistency;
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
public class SeckillWarmUpService implements CacheReheater, CacheConsistency {

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
                ? stockService.resetBuckets(activity, plan)
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

    /** 自检抽样上限：每条要读 16 个桶 + 一次 DB，刻意小 */
    static final int CONSISTENCY_SAMPLE = 5;

    /**
     * 抽样内"分桶余量合计 + 已售 != 总库存"的 ONLINE 活动数（与冒烟、复位脚本同一条恒等式）。
     * 任一桶键缺失也算不符——那是要重做预热的信号，不是"余量为 0"。
     *
     * <p>-1 = 判定不了（Redis 不可达等）。把它报成 0 就是本段存在的理由所要防的那件事。</p>
     */
    @Override
    public int mismatchCount() {
        try {
            List<SeckillActivityEntity> online = activityMapper.selectList(
                    new LambdaQueryWrapper<SeckillActivityEntity>()
                            .eq(SeckillActivityEntity::getStatus, STATUS_ONLINE)
                            .last("LIMIT " + CONSISTENCY_SAMPLE));
            int mismatch = 0;
            for (SeckillActivityEntity activity : online) {
                int buckets = bucketsOf(activity);
                List<Long> current = stockService.currentBucketStocks(activity.getActivityNo(), buckets);
                boolean missing = current.size() < buckets
                        || current.stream().anyMatch(v -> v == null || v < 0);
                long actual = current.stream()
                        .filter(v -> v != null && v >= 0).mapToLong(Long::longValue).sum();
                long expected = remainOf(activity.getTotalStock(), activity.getSoldStock());
                if (missing || actual != expected) {
                    mismatch++;
                }
            }
            return mismatch;
        } catch (RuntimeException e) {
            log.warn("[seckill] 一致性自检判定不了: {}", e.toString());
            return -1;
        }
    }
}
