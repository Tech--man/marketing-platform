package com.example.marketing.coupon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 上下线：status 只允许 ACTIVE / INACTIVE（与 {@code CouponTemplateService.STATUS_ACTIVE} 同值域） */
public record StatusUpdateRequest(
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE", message = "status 只能是 ACTIVE 或 INACTIVE") String status,
        @NotNull(message = "version 必填（乐观锁）") Integer version) {
}
