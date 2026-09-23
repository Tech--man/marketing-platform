package com.example.marketing.seckill.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 新建秒杀活动（③ 净新增能力：今天只有 init.sql 种子）。
 *
 * <p>没有 status 字段：新建一律落成 OFFLINE，要开闸得显式调上下线端点。
 * 活动还没确认就把分桶按总量建起来，等于给一个没人复核过的价格开了闸。</p>
 */
public record SeckillActivityCreateRequest(
        @NotBlank(message = "activityNo 必填") String activityNo,
        @NotNull(message = "itemId 必填") Long itemId,
        @NotBlank(message = "itemName 必填") String itemName,
        @NotNull(message = "秒杀价必填") @DecimalMin(value = "0.00", message = "秒杀价不能为负") BigDecimal seckillPrice,
        @NotNull @Min(value = 1, message = "总库存至少为 1") Integer totalStock,
        LocalDateTime startTime, LocalDateTime endTime) {
}
