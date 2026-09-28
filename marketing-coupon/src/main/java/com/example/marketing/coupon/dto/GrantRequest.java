package com.example.marketing.coupon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 领券请求。requestId 由客户端生成（UUID），是全链路幂等键。
 *
 * <p>userId 不由客户端填：{@code CouponController} 用验过签名的身份重建这个记录，
 * 客户端送来的那一个在进入服务层之前就已经被替换掉了。</p>
 */
public record GrantRequest(
        @NotBlank(message = "requestId 必填") String requestId,
        /** 服务端填充：控制器用验过签名的身份重建本记录。这里不加 @NotNull ——
         * 约束跑在反序列化之后、控制器填值之前，加了就等于拒绝所有合法请求。 */
        Long userId,
        @NotBlank(message = "templateNo 必填") String templateNo) {
}
