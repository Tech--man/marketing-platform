package com.example.marketing.coupon.dto;

import com.example.marketing.coupon.infrastructure.entity.CouponTemplateEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 券模板的后台视图：带 version 供乐观锁回传，带 remain 让人看得见实时余量 */
public record TemplateView(
        String templateNo, String activityNo, String name, String couponType,
        BigDecimal faceValue, BigDecimal thresholdAmount, Integer totalStock,
        Integer perUserLimit, Integer validDays, String status,
        LocalDateTime startTime, LocalDateTime endTime, Integer version) {

    public static TemplateView from(CouponTemplateEntity e) {
        return new TemplateView(e.getTemplateNo(), e.getActivityNo(), e.getName(), e.getCouponType(),
                e.getFaceValue(), e.getThresholdAmount(), e.getTotalStock(), e.getPerUserLimit(),
                e.getValidDays(), e.getStatus(), e.getStartTime(), e.getEndTime(), e.getVersion());
    }
}
