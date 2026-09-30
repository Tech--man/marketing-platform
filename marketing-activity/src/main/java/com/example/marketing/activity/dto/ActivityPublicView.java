package com.example.marketing.activity.dto;

import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 活动公开视图（2026-09-29 审查收口）：{@code GET /api/activity/{no}} 在网关
 * permit-paths 免 token，此前直接返回实体——灰度白名单 userId CSV（个人信息）与
 * 灰度配置（运营策略）跟着出门。H5 实际只用以下字段（目录/详情页的预算进度条
 * 是刻意的演示功能，budgetAmount/usedAmount 保留）。
 *
 * <p>刻意不出去的：{@code grayPercent}/{@code grayWhitelist}（运营策略+个人信息）、
 * {@code version}（管理面乐观锁专用）、{@code id}/{@code remark}（内部字段）。
 * 需要它们的是 {@code /api/admin/activities**}（token 后面）。</p>
 */
@Data
public class ActivityPublicView {

    private String activityNo;
    private String name;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 预算总额（元）——H5 目录/详情页进度条的刻意展示 */
    private BigDecimal budgetAmount;
    /** 已消耗（元）——同上 */
    private BigDecimal usedAmount;

    public static ActivityPublicView from(ActivityEntity entity) {
        ActivityPublicView view = new ActivityPublicView();
        view.setActivityNo(entity.getActivityNo());
        view.setName(entity.getName());
        view.setStatus(entity.getStatus());
        view.setStartTime(entity.getStartTime());
        view.setEndTime(entity.getEndTime());
        view.setBudgetAmount(entity.getBudgetAmount());
        view.setUsedAmount(entity.getUsedAmount());
        return view;
    }
}
