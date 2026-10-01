package com.example.marketing.coupon.service;

import com.example.marketing.common.redis.LuaScripts;
import com.example.marketing.coupon.infrastructure.entity.CouponTemplateEntity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 券库存 Redis 守卫：Lua 原子"预扣 + 个人限领"，防超发第一道闸门。
 *
 * <p>DB 落库由 MQ 消费端异步完成，本服务只保证 Redis 口径的强一致；
 * 最终一致由本地消息表补偿 + 对账任务保证。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponStockService {

    private static final RedisScript<Long> DEDUCT = LuaScripts.ofLong("lua/deduct_stock.lua");
    private static final RedisScript<Long> ROLLBACK = LuaScripts.ofLong("lua/rollback_stock.lua");

    /** 个人限领计数 TTL 下限（30 天）：实际取 max(下限, 模板有效期剩余) */
    private static final Duration USER_KEY_TTL_FLOOR = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /** 预扣结果 */
    @Getter
    public enum DeductResult {
        SUCCESS, SOLD_OUT, EXCEED_LIMIT, NOT_WARMED;

        public static DeductResult of(Long code) {
            if (code == null) {
                return SOLD_OUT;
            }
            return switch (code.intValue()) {
                case 1 -> SUCCESS;
                case 0 -> SOLD_OUT;
                case -1 -> EXCEED_LIMIT;
                default -> NOT_WARMED;
            };
        }
    }

    /**
     * 原子预扣（库存 + 个人限领同时校验，单命令内完成，无竞态窗口）。
     *
     * <p>限领计数 TTL 由调用方按模板有效期传入（2026-09-29 审查第四批）：固定 30 天
     * 会把"活动内限领 N 张"稀释成"每 30 天限领 N 张"——种子模板有效期 365 天，
     * 长周期活动人均发券量放大 12 倍。TTL 必须覆盖模板剩余有效期。</p>
     */
    public DeductResult deduct(Long templateId, Long userId, int quantity, int perUserLimit,
                               Duration userKeyTtl) {
        Long ret = redisTemplate.execute(DEDUCT,
                List.of(stockKey(templateId), userKey(templateId, userId)),
                String.valueOf(quantity), String.valueOf(perUserLimit),
                String.valueOf(userKeyTtl.toSeconds()));
        DeductResult result = DeductResult.of(ret);
        if (result != DeductResult.SUCCESS) {
            log.info("[stock] 预扣拒绝 template={}, user={}, result={}", templateId, userId, result);
        }
        return result;
    }

    /** 限领计数 TTL：max(下限 30 天, 模板有效期剩余)——下限兜住 endTime 缺失的模板 */
    public static Duration userKeyTtl(java.time.LocalDateTime endTime) {
        if (endTime == null) {
            return USER_KEY_TTL_FLOOR;
        }
        Duration remaining = Duration.between(java.time.LocalDateTime.now(), endTime);
        return remaining.compareTo(USER_KEY_TTL_FLOOR) > 0 ? remaining : USER_KEY_TTL_FLOOR;
    }

    /**
     * 回补（异步落库最终失败/人工回收时）。
     *
     * <p>W2.2（2026-09-30 第二轮复审）：库存键不存在时 Lua 返回 -1（两边都不动）——
     * 原实现对缺失键无条件 INCRBY，会凭空创建幽灵库存键（Redis 重启/overwrite 窗口/
     * reheat(force) 后的迟到回补）。落空只计数告警，残余差值由 coupon mismatch
     * 恒等式暴露（方向偏少，不会超发）。</p>
     *
     * <p><b>幂等去重</b>（2026-10-01 审计 P3）：dedupToken = 这次预扣的身份
     * （领券即 requestId）。同一次预扣的回补可能被多条补偿路径重复触发（重试 +
     * 手工 redrive 会走到同一段归还逻辑），Lua 内 SET NX 保证同 token 只真还一次，
     * 二次到达返回 -2（只计数，不动账）——否则同一张券的库存被还两份。
     * 去重 TTL 取 30 天下限（与限领计数同窗）：覆盖补偿定时器 + FAILED 90 天
     * 归档前的人工 redrive 窗口。</p>
     */
    public void rollback(Long templateId, Long userId, int quantity, String dedupToken) {
        // N-6②（复审）：空白 token 不能拼出共享常量键 coupon:rollback:null——坏载荷分支
        // （invalidPayload）恰恰发生在"载荷已经坏了"时，requestId 可能缺失/空白，之后
        // 所有同类回补都会撞同一个键被 -2 静默吞掉（30 天 TTL 内回补通道整体失效）。
        // 空白时改用随机 token：放弃去重（重投的同一坏事件可能真还两次，方向偏松但
        // 有计数可见），比"静默全吞"（方向偏紧且不可见）可观测。
        boolean noToken = dedupToken == null || dedupToken.isBlank();
        if (noToken) {
            io.micrometer.core.instrument.Counter.builder("coupon.grant.rollback_no_token")
                    .register(meterRegistry).increment();
            log.warn("[stock] 回补去重 token 缺失（调用方应传 requestId），本次不去重直接回补 templateId={}",
                    templateId);
        }
        String token = noToken ? "notoken-" + java.util.UUID.randomUUID() : dedupToken;
        Long result = redisTemplate.execute(ROLLBACK,
                List.of(stockKey(templateId), userKey(templateId, userId),
                        "coupon:rollback:" + token),
                String.valueOf(quantity), String.valueOf(USER_KEY_TTL_FLOOR.toSeconds()));
        if (result != null && result == -1L) {
            io.micrometer.core.instrument.Counter.builder("coupon.grant.rollback_nokey")
                    .register(meterRegistry).increment();
            log.warn("[stock] 回补时库存键不存在，跳过（幽灵键防护；残余差值由 mismatch 暴露）templateId={}",
                    templateId);
        } else if (result != null && result == -2L) {
            io.micrometer.core.instrument.Counter.builder("coupon.grant.rollback_dedup")
                    .register(meterRegistry).increment();
            log.warn("[stock] 回补 token 已用过的幂等重放，跳过（防窄双退）templateId={}, token={}",
                    templateId, dedupToken);
        }
    }

    /** 预热库存（SETNX：重复调用/多实例并发安全；已存在则不覆盖，防止重启回涨库存） */
    public void warmIfAbsent(CouponTemplateEntity template, long remainStock) {
        Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(stockKey(template.getId()), String.valueOf(remainStock));
        if (Boolean.TRUE.equals(ok)) {
            log.info("[stock] 预热模板 {} 库存 {}", template.getTemplateNo(), remainStock);
        }
    }

    /**
     * 覆盖式重建库存：先 DEL 再 SET。
     *
     * <p>只在运营显式改了 total_stock 之后用（{@code reheat(force=true)}）。代价是这一瞬间
     * 在途的预扣会被回退成"多出来的库存"，方向偏松，因此它必须是个需要显式确认的运维动作。</p>
     */
    public void overwrite(CouponTemplateEntity template, long remainStock) {
        String key = stockKey(template.getId());
        redisTemplate.delete(key);
        redisTemplate.opsForValue().set(key, String.valueOf(remainStock));
        log.info("[stock] 覆盖重建模板 {} 库存 {}", template.getTemplateNo(), remainStock);
    }

    public boolean isWarmed(Long templateId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(stockKey(templateId)));
    }

    public Long remainStock(Long templateId) {
        String value = redisTemplate.opsForValue().get(stockKey(templateId));
        return value == null ? null : Long.parseLong(value);
    }

    private String stockKey(Long templateId) {
        return "coupon:stock:" + templateId;
    }

    private String userKey(Long templateId, Long userId) {
        return "coupon:user:" + templateId + ":" + userId;
    }
}
