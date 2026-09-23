package com.example.marketing.coupon.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 新建券模板（③ 净新增能力：今天模板只能靠 init.sql 种子写进库）。
 *
 * <p>不带 version：新建就是新建，改库存/改状态是另外两个端点，
 * 混在一起会出现"编号写错就顺手造一张模板"的误操作。</p>
 */
public record TemplateCreateRequest(
        @NotBlank(message = "templateNo 必填") String templateNo,
        @NotBlank(message = "activityNo 必填") String activityNo,
        @NotBlank(message = "name 必填") String name,
        @NotBlank(message = "couponType 必填") String couponType,
        @NotNull @DecimalMin(value = "0.00", message = "面额不能为负") BigDecimal faceValue,
        @NotNull @DecimalMin(value = "0.00", message = "门槛不能为负") BigDecimal thresholdAmount,
        @NotNull @Min(value = 1, message = "库存至少为 1") Integer totalStock,
        @NotNull @Min(value = 1, message = "每人限领至少为 1") Integer perUserLimit,
        @NotNull @Min(value = 1, message = "有效天数至少为 1") Integer validDays,
        LocalDateTime startTime, LocalDateTime endTime) {
}
