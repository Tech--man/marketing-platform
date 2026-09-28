package com.example.marketing.coupon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 核销请求：orderNo 是核销幂等的第二层凭证（同单重复核销返回原结果）。
 *
 * <p>userId 同 {@link GrantRequest}：由控制器从验过签名的身份填入，
 * 下游那句“券不属于该用户”因此才是真的在验本人，而不是在比对两个调用方自报的值。</p>
 */
public record ConsumeRequest(
        @NotBlank(message = "couponCode 必填") String couponCode,
        /** 同 GrantRequest：由服务端从验过的身份填入，不参与入参校验。 */
        Long userId,
        @NotBlank(message = "orderNo 必填") String orderNo) {
}
