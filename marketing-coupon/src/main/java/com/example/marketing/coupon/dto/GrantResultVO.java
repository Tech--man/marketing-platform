package com.example.marketing.coupon.dto;

/**
 * 领券结果（轮询查询用）。
 *
 * @param status     SUCCESS / PROCESSING / FAILED
 * @param couponCode 成功时返回券码
 * @param message    失败原因等
 */
public record GrantResultVO(String status, String couponCode, String message) {

    public static GrantResultVO success(String couponCode) {
        return new GrantResultVO("SUCCESS", couponCode, "领取成功");
    }

    public static GrantResultVO processing() {
        return new GrantResultVO("PROCESSING", null, "发放中，请稍后重试查询");
    }

    public static GrantResultVO failed(String message) {
        return new GrantResultVO("FAILED", null, message);
    }
}
