package com.example.marketing.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.marketing.activity.domain.ActivityEntityConverter;
import com.example.marketing.activity.domain.ActivityEvent;
import com.example.marketing.activity.domain.ActivityStateMachine;
import com.example.marketing.activity.domain.ActivityStatus;
import com.example.marketing.activity.dto.CreateActivityRequest;
import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import com.example.marketing.activity.infrastructure.mapper.ActivityMapper;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 活动生命周期管理：创建、状态机流转（含乐观锁）、上线时预算预热。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityService {

    private final ActivityMapper activityMapper;
    private final BudgetService budgetService;

    public ActivityEntity create(CreateActivityRequest request) {
        if (activityMapper.exists(Wrappers.<ActivityEntity>lambdaQuery()
                .eq(ActivityEntity::getActivityNo, request.activityNo()))) {
            throw new BizException(ErrorCode.BIZ_ERROR, "活动编号已存在: " + request.activityNo());
        }
        ActivityEntity entity = ActivityEntityConverter.fromRequest(request);
        activityMapper.insert(entity);
        log.info("[activity] 创建草稿活动 {}", entity.getActivityNo());
        return entity;
    }

    /**
     * 状态机流转。乐观锁冲突抛业务异常由调用方重试。
     */
    @Transactional(rollbackFor = Exception.class)
    public ActivityEntity transition(String activityNo, ActivityEvent event) {
        ActivityEntity entity = getByNo(activityNo);
        ActivityStatus current = ActivityStatus.valueOf(entity.getStatus());
        ActivityStatus target = ActivityStateMachine.next(current, event);

        entity.setStatus(target.name());
        int updated = activityMapper.updateById(entity);
        if (updated == 0) {
            throw new BizException(ErrorCode.BIZ_ERROR, "并发更新冲突，请重试");
        }
        // 上线动作的副作用：预算预热（SETNX 幂等）
        if (target == ActivityStatus.ONLINE) {
            budgetService.warmIfAbsent(activityNo, entity.getBudgetAmount());
        }
        log.info("[activity] {} 状态流转 {} -[{}]-> {}", activityNo, current, event, target);
        return entity;
    }

    public ActivityEntity getByNo(String activityNo) {
        ActivityEntity entity = activityMapper.selectOne(Wrappers.<ActivityEntity>lambdaQuery()
                .eq(ActivityEntity::getActivityNo, activityNo));
        if (entity == null) {
            throw new BizException(ErrorCode.BIZ_ERROR, "活动不存在: " + activityNo);
        }
        return entity;
    }

    /** 活动是否可参与（供券/秒杀/优惠等下游校验） */
    public boolean participatable(String activityNo) {
        ActivityEntity entity = getByNo(activityNo);
        return ActivityStatus.valueOf(entity.getStatus()).participatable();
    }
}
