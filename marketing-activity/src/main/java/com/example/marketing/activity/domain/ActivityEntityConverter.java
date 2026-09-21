package com.example.marketing.activity.domain;

import com.example.marketing.activity.dto.CreateActivityRequest;
import com.example.marketing.activity.infrastructure.entity.ActivityEntity;

import java.math.BigDecimal;

/**
 * 请求 → 实体转换（领域边界：DTO 不穿透到持久层）。
 */
public final class ActivityEntityConverter {

    private ActivityEntityConverter() {
    }

    public static ActivityEntity fromRequest(CreateActivityRequest request) {
        ActivityEntity entity = new ActivityEntity();
        entity.setActivityNo(request.activityNo());
        entity.setName(request.name());
        entity.setStatus(ActivityStatus.DRAFT.name());
        entity.setStartTime(request.startTime());
        entity.setEndTime(request.endTime());
        entity.setBudgetAmount(request.budgetAmount() == null ? BigDecimal.ZERO : request.budgetAmount());
        entity.setUsedAmount(BigDecimal.ZERO);
        entity.setRemark(request.remark());
        entity.setVersion(0);
        return entity;
    }
}
