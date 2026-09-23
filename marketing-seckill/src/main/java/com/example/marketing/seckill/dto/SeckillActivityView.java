package com.example.marketing.seckill.dto;

import com.example.marketing.seckill.infrastructure.entity.SeckillActivityEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀活动的后台视图。
 *
 * <p>{@code soldStock} 与 {@code version} 都必须带出去：前者决定"库存还能不能改小"，
 * 后者是下一次编辑的乐观锁期望值。少一个，前端就只能靠猜。</p>
 */
public record SeckillActivityView(
        String activityNo, Long itemId, String itemName, BigDecimal seckillPrice,
        Integer totalStock, Integer soldStock, Integer buckets, String status,
        LocalDateTime startTime, LocalDateTime endTime, Integer version) {

    public static SeckillActivityView from(SeckillActivityEntity e) {
        return new SeckillActivityView(e.getActivityNo(), e.getItemId(), e.getItemName(),
                e.getSeckillPrice(), e.getTotalStock(), e.getSoldStock(), e.getBuckets(),
                e.getStatus(), e.getStartTime(), e.getEndTime(), e.getVersion());
    }
}
