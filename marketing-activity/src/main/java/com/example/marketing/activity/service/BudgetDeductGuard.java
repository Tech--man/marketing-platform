package com.example.marketing.activity.service;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * C 端预算扣减的滥用闸（H10，2026-09-29 架构审查收口）。
 *
 * <p>{@code POST /api/activity/{no}/budget/deduct} 是 C 端保留的交易写，幂等键 bizKey
 * 由调用方自报——换一个键就是一次真扣。收口前它"无身份绑定、无频控、无单笔上限"，
 * 任何一个持消费者 token 的账号都能逐笔把活动预算抽干，且每笔都是合法流水、审计无异常。
 * 这道闸补三样：登录身份（uid 维度计数）、每用户每活动每窗口的次数上限、单笔金额上限。</p>
 *
 * <p>Redis 异常时<b>放行</b>：与 LoginGuard 同一方向（宁少挡不误锁）——它拦的是恶意
 * 脚本刷键，拦不住的代价是"恶意流量回到只有网关限流的基线"，误拦的代价是正常用户
 * 连正常单都下不了。预算硬闸仍在 {@link BudgetService}（Redis 余额 + BUDGET_NOT_ENOUGH）。</p>
 *
 * <p>计数用一段内联 Lua（INCR + 首次 EXPIRE 原子化）：拆成两条命令的话，EXPIRE 那步
 * 失败会留下一个无 TTL 的键，该用户从此永久"请求过于频繁"——同族坑已在 LoginGuard
 * 的两步写里记过一笔，这里不再新增一份。</p>
 */
@Slf4j
@Service
public class BudgetDeductGuard {

    private static final String KEY_PREFIX = "mkt:budget:deduct:guard:";

    /** INCR 命中 1 时才补 EXPIRE：窗口固定，不做滑动刷新（刷了就变成"持续调用永不过期"） */
    private static final RedisScript<Long> WINDOW_INCR = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) end "
                    + "return c",
            Long.class);

    private final StringRedisTemplate redis;

    private final int maxPerWindow;
    private final int windowSeconds;
    private final long maxAmountCents;

    public BudgetDeductGuard(StringRedisTemplate redis,
                             @Value("${marketing.activity.budget-deduct.max-per-window:10}")
                             int maxPerWindow,
                             @Value("${marketing.activity.budget-deduct.window-seconds:60}")
                             int windowSeconds,
                             @Value("${marketing.activity.budget-deduct.max-amount-cents:100000}")
                             long maxAmountCents) {
        this.redis = redis;
        this.maxPerWindow = maxPerWindow;
        this.windowSeconds = windowSeconds;
        this.maxAmountCents = maxAmountCents;
    }

    /** 单笔金额上限：纯校验，不碰 Redis。超限直接 40000 并点名上限，别让人猜 */
    public void checkAmount(long amountCents) {
        if (amountCents > maxAmountCents) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "单笔预算扣减上限 " + maxAmountCents + " 分，当前 " + amountCents + " 分");
        }
    }

    /** 每用户每活动的固定窗口计数：超限 42900（与网关限流同码，客户端处理动作一致） */
    public void checkRate(String activityNo, long uid) {
        String key = KEY_PREFIX + activityNo + ":" + uid;
        long count;
        try {
            Long result = redis.execute(WINDOW_INCR, List.of(key), String.valueOf(windowSeconds));
            count = result == null ? 0L : result;
        } catch (RuntimeException e) {
            log.warn("[budget-guard] 计数不可用，本请求放行（fail-open，预算硬闸仍在 BudgetService）: {}",
                    e.toString());
            return;
        }
        if (count > maxPerWindow) {
            throw BizException.of(ErrorCode.TOO_MANY_REQUESTS,
                    "预算扣减过于频繁（每 " + windowSeconds + "s 最多 " + maxPerWindow + " 次）");
        }
    }
}
