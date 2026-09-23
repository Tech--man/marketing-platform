package com.example.marketing.coupon.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 改总库存（减少不得低于已发出数，否则余量会被钳成 0 而看起来像"卖光了"） */
public record StockUpdateRequest(
        @NotNull @Min(value = 1, message = "总库存至少为 1") Integer totalStock,
        @NotNull(message = "version 必填（乐观锁）") Integer version) {
}
