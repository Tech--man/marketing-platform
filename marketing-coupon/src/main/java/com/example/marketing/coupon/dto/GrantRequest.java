package com.example.marketing.coupon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 领券请求。requestId 由客户端生成（UUID），是全链路幂等键。
 */
public record GrantRequest(
        @NotBlank(message = "requestId 必填") String requestId,
        @NotNull(message = "userId 必填") Long userId,
        @NotBlank(message = "templateNo 必填") String templateNo) {
}
