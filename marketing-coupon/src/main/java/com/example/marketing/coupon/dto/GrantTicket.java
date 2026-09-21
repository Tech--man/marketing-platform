package com.example.marketing.coupon.dto;

/**
 * 领券受理凭证（异步落库，客户端凭 requestId 轮询结果）。
 *
 * @param requestId 幂等键
 * @param status    ACCEPTED（已进入落库队列）
 */
public record GrantTicket(String requestId, String status) {

    public static GrantTicket accepted(String requestId) {
        return new GrantTicket(requestId, "ACCEPTED");
    }
}
