package com.example.marketing.activity.service;

import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import com.example.marketing.activity.infrastructure.mapper.ActivityMapper;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.cache.CacheReheater;
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
 * 任一环节失败均可安全重放（唯一索引是 <b>(activity_no, biz_key)</b>，见地雷 B）。对账口径：
 * Redis 剩余额 == 总预算 - SUM(流水 DEDUCT) + SUM(流水 REFUND)。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetService implements CacheReheater {

    private static final RedisScript<Long> DEDUCT = LuaScripts.ofLong("lua/deduct_budget.lua");

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ActivityMapper activityMapper;

    /** 预热预算（活动创建时调用；SETNX 语义，重复调用安全） */
    public void warmIfAbsent(String activityNo, BigDecimal budgetYuan) {
        warmCentsIfAbsent(activityNo, toCents(budgetYuan));
    }

    private void warmCentsIfAbsent(String activityNo, long cents) {
        redisTemplate.opsForValue().setIfAbsent(budgetKey(activityNo), String.valueOf(cents));
    }

    /**
     * 按对账口径重算剩余预算（分）：总预算 + SUM(流水)。
     *
     * <p>DEDUCT 流水按约定存<b>负数</b>（见 {@link #deduct}），REFUND 存正数，所以这里是加。
     * 只读 DB，绝不读 Redis —— 这个方法的用途恰恰是 Redis 值不可信时给出权威值。</p>
     */
    long computeRemainCents(String activityNo) {
        ActivityEntity activity = requireActivity(activityNo);
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM budget_flow"
                        + " WHERE activity_no = ? AND type IN ('DEDUCT', 'REFUND')",
                Long.class, activityNo);
        return toCents(activity.getBudgetAmount()) + (sum == null ? 0L : sum);
    }

    @Override
    public String type() {
        return "budget";
    }

    /**
     * 重预热预算键。
     *
     * @param force false = 只补缺（SETNX，不动已有值）；true = 删掉按对账口径重建 ——
     *              运营改完 budget_amount 之后必须走这条，否则键还在、改动作无用（地雷 A）。
     *              代价：此刻有请求正在"已写流水、未扣 Redis"的中间态上，会被多扣一次，
     *              方向偏保守（少给不会超支）。
     */
    @Override
    public CacheReheater.Result reheat(String activityNo, boolean force) {
        long target = computeRemainCents(activityNo);
        String key = budgetKey(activityNo);
        Long current = readCents(key);
        long before = current == null ? -1L : current;
        if (force) {
            redisTemplate.delete(key);
            redisTemplate.opsForValue().set(key, String.valueOf(target));
            return new Result(type(), activityNo, before, target, FORMULA);
        }
        warmCentsIfAbsent(activityNo, target);
        Long after = readCents(key);
        return new Result(type(), activityNo, before, after == null ? before : after, FORMULA);
    }

    private static final String FORMULA = "总预算(分) + SUM(budget_flow.amount_cents WHERE DEDUCT|REFUND)";

    private Long readCents(String key) {
        String value = redisTemplate.opsForValue().get(key);
        return value == null ? null : Long.parseLong(value);
    }

    /**
     * 扣减预算（幂等）。
     *
     * <p>幂等键的作用域是<b>(活动, bizKey)</b>，不是 bizKey 单独：这个 bizKey 由调用方提供
     * （{@code POST /api/activity/{no}/budget/deduct}），两个活动复用同一个编号是正常用法，
     * 早先的全局唯一索引会让第二次<b>既不扣款也返回成功</b>（地雷 B）。</p>
     *
     * @param amountCents 扣减金额（分）
     * @param bizKey      调用方业务唯一键（活动内唯一即可）
     */
    public DeductOutcome deduct(String activityNo, long amountCents, String bizKey) {
        if (amountCents <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "扣减金额必须为正");
        }
        // 1. 流水占位（先落审计再扣 Redis，失败即回删，保证 Redis 与流水一致方向偏保守）
        int inserted = jdbcTemplate.update(
                "INSERT IGNORE INTO budget_flow (activity_no, biz_key, amount_cents, type) VALUES (?, ?, ?, 'DEDUCT')",
                activityNo, bizKey, -amountCents);
        if (inserted == 0) {
            // 同活动同键重复进入：钱已经扣过，回放而不是再扣一次
            log.info("[budget] 重复扣减请求幂等回放 activityNo={}, bizKey={}", activityNo, bizKey);
            return DeductOutcome.REPLAYED;
        }
        // 2. Redis 原子扣减
        Long result = evalDeduct(activityNo, amountCents);
        if (result == null || result == -1L) {
            // 缺键：本笔已作为 DEDUCT 落进流水，所以直接把"对账后的余额"写出去，
            // 不能再 DECRBY 一次（会把这笔扣款算两遍，实测 Redis 恒比权威值少一笔）；
            // 也不能先回删流水再重试（会留下"扣了没记"的缺口，对账时当成可用余额放出去）。
            long reconciled = computeRemainCents(activityNo);
            if (reconciled < 0) {
                rollbackFlow(activityNo, bizKey);
                throw BizException.of(ErrorCode.BUDGET_NOT_ENOUGH);
            }
            redisTemplate.opsForValue().set(budgetKey(activityNo), String.valueOf(reconciled));
            result = 1L;
        }
        if (result == null || result == 0L) {
            // 只有真失败才回删占位
            rollbackFlow(activityNo, bizKey);
            throw BizException.of(ErrorCode.BUDGET_NOT_ENOUGH);
        }
        // 3. DB 兜底口径累加（生产高并发场景可改批量异步汇总，此处保持同步便于对账演示）
        jdbcTemplate.update("UPDATE activity SET used_amount = used_amount + ? WHERE activity_no = ?",
                BigDecimal.valueOf(amountCents).movePointLeft(2), activityNo);
        return DeductOutcome.DEDUCTED;
    }

    /**
     * 扣减结果。原来 void + code=0 让"幂等回放"与"真的扣了钱"在响应上无法区分，
     * 运营/调用方看到成功就以为扣成了 —— 现在语义进 data。
     */
    public enum DeductOutcome {
        DEDUCTED, REPLAYED;
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

    /**
     * 回删本活动的占位。必须带 activity_no —— 原来只按 biz_key 删，
     * 而 bizKey 由调用方提供，一次失败的回滚能把<b>另一个活动</b>已成功的扣款记录抹掉，
     * 对账口径就会把那笔钱当成没扣过（地雷 B 的另一半）。
     */
    void rollbackFlow(String activityNo, String bizKey) {
        jdbcTemplate.update("DELETE FROM budget_flow WHERE activity_no = ? AND biz_key = ? AND type = 'DEDUCT'",
                activityNo, bizKey);
    }

    private ActivityEntity requireActivity(String activityNo) {
        ActivityEntity activity = activityMapper.selectOne(
                Wrappers.<ActivityEntity>lambdaQuery().eq(ActivityEntity::getActivityNo, activityNo));
        if (activity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "活动不存在: " + activityNo);
        }
        return activity;
    }

    /** 元 → 分。预算与流水的权威计算全在"分"上做，避免 BigDecimal 与 long 混算 */
    private long toCents(BigDecimal yuan) {
        return yuan.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    private String budgetKey(String activityNo) {
        return "activity:budget:" + activityNo;
    }
}
