package com.example.marketing.coupon.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B4-4 回归（2026-09-29 审查第四批）：限领计数 TTL 必须覆盖模板剩余有效期。
 *
 * <p>固定 30 天会把"活动内限领 N 张"稀释成"每 30 天限领 N 张"——种子模板有效期
 * 365 天，长周期活动人均发券量放大 12 倍。TTL = max(下限 30 天, endTime - now)。</p>
 */
class CouponStockServiceTtlTest {

    @Test
    @DisplayName("模板剩余有效期超过 30 天下限 → TTL 覆盖到 endTime")
    void longTemplateCoversWholeValidity() {
        LocalDateTime end = LocalDateTime.now().plusDays(365);

        long ttl = CouponStockService.userKeyTtl(end).toDays();

        assertEquals(364, ttl, "365 天有效期的模板，TTL 必须约等于整段有效期（364±舍入），而不是 30");
    }

    @Test
    @DisplayName("endTime 缺失或已过 → 兜 30 天下限")
    void missingOrPastEndsFallBackToFloor() {
        assertEquals(30, CouponStockService.userKeyTtl(null).toDays());
        assertEquals(30, CouponStockService.userKeyTtl(LocalDateTime.now().minusDays(1)).toDays());
    }

    @Test
    @DisplayName("剩余有效期短于 30 天 → 仍兜下限（保守方向：多限不远期）")
    void shortTemplateUsesFloor() {
        assertEquals(30, CouponStockService.userKeyTtl(LocalDateTime.now().plusDays(3)).toDays());
    }
}
