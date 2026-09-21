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

    /** 个人限领计数 TTL：覆盖活动周期即可（30 天） */
    private static final Duration USER_KEY_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;

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
     */
    public DeductResult deduct(Long templateId, Long userId, int quantity, int perUserLimit) {
        Long ret = redisTemplate.execute(DEDUCT,
                List.of(stockKey(templateId), userKey(templateId, userId)),
                String.valueOf(quantity), String.valueOf(perUserLimit),
                String.valueOf(USER_KEY_TTL.toSeconds()));
        DeductResult result = DeductResult.of(ret);
        if (result != DeductResult.SUCCESS) {
            log.info("[stock] 预扣拒绝 template={}, user={}, result={}", templateId, userId, result);
        }
        return result;
    }

    /** 回补（异步落库最终失败/人工回收时） */
    public void rollback(Long templateId, Long userId, int quantity) {
        redisTemplate.execute(ROLLBACK,
                List.of(stockKey(templateId), userKey(templateId, userId)), String.valueOf(quantity));
    }

    /** 预热库存（SETNX：重复调用/多实例并发安全；已存在则不覆盖，防止重启回涨库存） */
    public void warmIfAbsent(CouponTemplateEntity template, long remainStock) {
        Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(stockKey(template.getId()), String.valueOf(remainStock));
        if (Boolean.TRUE.equals(ok)) {
            log.info("[stock] 预热模板 {} 库存 {}", template.getTemplateNo(), remainStock);
        }
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
