package com.example.marketing.coupon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 核销请求：orderNo 是核销幂等的第二层凭证（同单重复核销返回原结果）。
 */
public record ConsumeRequest(
        @NotBlank(message = "couponCode 必填") String couponCode,
        @NotNull(message = "userId 必填") Long userId,
        @NotBlank(message = "orderNo 必填") String orderNo) {
}
