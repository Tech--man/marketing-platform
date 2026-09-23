package com.example.marketing.seckill.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 改总库存：减少不得低于已售数；活动在线时同事务重建分桶 */
public record StockEditRequest(
        @NotNull @Min(value = 1, message = "总库存至少为 1") Integer totalStock,
        @NotNull(message = "version 必填（乐观锁）") Integer version) {
}
