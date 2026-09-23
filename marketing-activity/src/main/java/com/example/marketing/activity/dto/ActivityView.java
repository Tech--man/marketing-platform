package com.example.marketing.activity.dto;

import com.example.marketing.activity.infrastructure.entity.ActivityEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 后台列表用的活动视图。
 *
 * <p>VO 只在 admin 侧强制（母版 §6.2）：C 端 {@code GET /api/activity/{no}} 仍返回实体，
 * 免得把基线断言卷进无关改动。</p>
 *
 * <p>{@code version} 必须带出去 —— 前端下一次编辑要拿它当乐观锁的期望值。
 * 不带就等于每次编辑都是"我看的是三秒前的世界"，而 41008 永远不会出现。</p>
 */
public record ActivityView(
        String activityNo, String name, String status,
        BigDecimal budgetAmount, BigDecimal usedAmount,
        Integer grayPercent, String grayWhitelist, Integer version,
        LocalDateTime startTime, LocalDateTime endTime) {

    public static ActivityView from(ActivityEntity e) {
        return new ActivityView(e.getActivityNo(), e.getName(), e.getStatus(),
                e.getBudgetAmount(), e.getUsedAmount(), e.getGrayPercent(), e.getGrayWhitelist(),
                e.getVersion(), e.getStartTime(), e.getEndTime());
    }
}
