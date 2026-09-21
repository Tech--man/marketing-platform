package com.example.marketing.activity.service;

import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import com.example.marketing.activity.infrastructure.mapper.ActivityMapper;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.redis.LuaScripts;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 预算控制：Redis Lua 原子预扣（高并发实时口径）+ budget_flow 流水幂等 + activity.used_amount DB 兜底。
 *
 * <p>一致性策略：预算属"资金"语义，采用预扣 + 流水先占位再扣 Redis；
 * 任一环节失败均可安全重放（biz_key 唯一索引）。对账口径：
 * Redis 剩余额 == 总预算 - SUM(流水 DEDUCT) + SUM(流水 REFUND)。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetService {

    private static final RedisScript<Long> DEDUCT = LuaScripts.ofLong("lua/deduct_budget.lua");

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ActivityMapper activityMapper;

    /** 预热预算（活动上线时调用；SETNX 语义，重复调用安全） */
    public void warmIfAbsent(String activityNo, BigDecimal budgetYuan) {
        String key = budgetKey(activityNo);
        redisTemplate.opsForValue().setIfAbsent(key, toCents(budgetYuan).toPlainString());
    }

    /**
     * 扣减预算（幂等：同 bizKey 重复调用直接视为成功）。
     *
     * @param amountCents 扣减金额（分）
     * @param bizKey      业务唯一键（如券发放请求 ID）
     */
    public void deduct(String activityNo, long amountCents, String bizKey) {
        if (amountCents <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "扣减金额必须为正");
        }
        // 1. 流水占位（先落审计再扣 Redis，失败即回删，保证 Redis 与流水一致方向偏保守）
        int inserted = jdbcTemplate.update(
                "INSERT IGNORE INTO budget_flow (activity_no, biz_key, amount_cents, type) VALUES (?, ?, ?, 'DEDUCT')",
                activityNo, bizKey, -amountCents);
        if (inserted == 0) {
            log.info("[budget] 重复扣减请求直接幂等返回 bizKey={}", bizKey);
            return;
        }
        // 2. Redis 原子扣减
        Long result = evalDeduct(activityNo, amountCents);
        if (result == null || result == -1L) {
            // 未预热：从 DB 补一次预算再重试
            rollbackFlow(bizKey);
            warmFromDb(activityNo);
            result = evalDeduct(activityNo, amountCents);
        }
        if (result == null || result == 0L) {
            rollbackFlow(bizKey);
            throw BizException.of(ErrorCode.BUDGET_NOT_ENOUGH);
        }
        // 3. DB 兜底口径累加（生产高并发场景可改批量异步汇总，此处保持同步便于对账演示）
        jdbcTemplate.update("UPDATE activity SET used_amount = used_amount + ? WHERE activity_no = ?",
                BigDecimal.valueOf(amountCents).movePointLeft(2), activityNo);
    }

    /** 剩余预算（分），供监控与查询 */
    public Long remainCents(String activityNo) {
        String value = redisTemplate.opsForValue().get(budgetKey(activityNo));
        return value == null ? null : Long.parseLong(value);
    }

    private Long evalDeduct(String activityNo, long amountCents) {
        return redisTemplate.execute(DEDUCT,
                List.of(budgetKey(activityNo)), String.valueOf(amountCents));
    }

    private void rollbackFlow(String bizKey) {
        jdbcTemplate.update("DELETE FROM budget_flow WHERE biz_key = ? AND type = 'DEDUCT'", bizKey);
    }

    private void warmFromDb(String activityNo) {
        ActivityEntity activity = activityMapper.selectOne(
                Wrappers.<ActivityEntity>lambdaQuery().eq(ActivityEntity::getActivityNo, activityNo));
        if (activity == null) {
            throw new BizException(ErrorCode.BIZ_ERROR, "活动不存在: " + activityNo);
        }
        warmIfAbsent(activityNo, activity.getBudgetAmount());
    }

    private BigDecimal toCents(BigDecimal yuan) {
        return yuan.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP);
    }

    private String budgetKey(String activityNo) {
        return "activity:budget:" + activityNo;
    }
}
