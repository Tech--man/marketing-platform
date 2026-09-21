package com.example.marketing.activity.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 创建活动请求。
 */
public record CreateActivityRequest(
        @NotBlank(message = "活动编号不能为空") String activityNo,
        @NotBlank(message = "活动名称不能为空") String name,
        @NotNull(message = "开始时间必填") LocalDateTime startTime,
        @NotNull(message = "结束时间必填") LocalDateTime endTime,
        @NotNull @DecimalMin(value = "0.00", message = "预算不能为负") BigDecimal budgetAmount,
        String remark) {
}
