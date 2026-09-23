package com.example.marketing.activity.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 后台改总预算。
 *
 * @param version 列表里带回来的乐观锁版本。<b>必填</b>：可选就等于
 *                "不传即默认覆盖别人"，那正是 ③ 要防的静默丢改动。
 */
public record BudgetUpdateRequest(
        @NotNull(message = "budgetAmount 必填")
        @DecimalMin(value = "0.00", message = "预算不能为负") BigDecimal budgetAmount,
        @NotNull(message = "version 必填（乐观锁）") Integer version,
        String remark) {
}
