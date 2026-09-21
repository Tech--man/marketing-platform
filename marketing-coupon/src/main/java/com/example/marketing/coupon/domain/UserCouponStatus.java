package com.example.marketing.coupon.domain;

/**
 * 用户券状态机：UNUSED → USED / EXPIRED（终态）。
 */
public enum UserCouponStatus {

    UNUSED,
    USED,
    EXPIRED;

    public boolean canConsume() {
        return this == UNUSED;
    }
}
