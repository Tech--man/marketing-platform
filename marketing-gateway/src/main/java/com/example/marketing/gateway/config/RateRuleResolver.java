package com.example.marketing.gateway.config;

import com.example.marketing.common.config.ConfigValues;
import org.springframework.stereotype.Component;

/**
 * 一次限流判定该用哪个阈值：在线快照 &gt; 本进程 yml/环境变量出厂值。
 *
 * <p>单独成一个纯类是为了可单测——filter 里那半条 reactive 链不好测，
 * 而"取错档的阈值"是最贵的一种错：LITE 拿到 FULL 的值就是把洪流灌进单机。</p>
 */
@Component
public class RateRuleResolver {

    /**
     * @param rule         本次判定用的规则；null = 该路由不限流（保持现状）
     * @param fromSnapshot 阈值来自在线快照而不是 yml
     * @param ignored      在线快照里有这个键但没被采纳（越界/类型不符），已在退回出厂值
     */
    public record Outcome(GatewayProperties.RateRule rule, boolean fromSnapshot, boolean ignored) {
    }

    /** 已告警过的路由：钳制是配置错误，每次请求都 warn 会把日志打爆 */
    private static final java.util.Set<String> CLAMP_WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public Outcome resolve(String routeId, GatewayProperties.RateRule ymlRule, ConfigValues values) {
        if (ymlRule == null) {
            // route 不在 rate-limit map 里 = 完全不限流。本段不改这条语义（只改"在 map 里时阈值从哪来"）。
            return new Outcome(null, false, false);
        }
        String key = GatewayConfigDefinitions.keyOf(routeId);
        if (!values.overridden(key)) {
            // 返回 yml 那个实例本身：不每请求新建对象，也绝不去改共享的 @ConfigurationProperties bean
            return new Outcome(clamped(routeId, ymlRule), false, values.degradedKeys().contains(key));
        }
        GatewayProperties.RateRule merged = new GatewayProperties.RateRule();
        merged.setLimit(clampLimit(routeId, values.intOr(key, (int) ymlRule.getLimit())));
        merged.setWindowSeconds(ymlRule.getWindowSeconds());
        return new Outcome(merged, true, false);
    }

    /**
     * 根因 C（2026-09-29 审查）：{@code RL_*=0} 经 env/yml 设入时没有任何闸门——
     * 0 在 Lua 里恒真等于<b>整条入口全拒</b>，恰是 GatewayConfigDefinitions 注释里
     * "下界取 1 而不是 0"要防的事，只是换了个输入框。在线快照路径有 schema 的
     * min=1 校验，这里给 yml/env 路径补同一道闸：limit 钳到 1 并告警一次。
     * 不改共享 bean（limit&lt;1 时返回钳制副本），每请求新建仅发生在错误配置期间。
     */
    private GatewayProperties.RateRule clamped(String routeId, GatewayProperties.RateRule rule) {
        if (rule.getLimit() >= 1) {
            return rule;
        }
        GatewayProperties.RateRule fixed = new GatewayProperties.RateRule();
        fixed.setLimit(clampLimit(routeId, rule.getLimit()));
        fixed.setWindowSeconds(rule.getWindowSeconds());
        return fixed;
    }

    private int clampLimit(String routeId, long limit) {
        if (limit >= 1) {
            return (int) limit;
        }
        if (CLAMP_WARNED.add(routeId)) {
            org.slf4j.LoggerFactory.getLogger(RateRuleResolver.class)
                    .warn("[rate-limit] route={} 的 limit={} 非法（0=整条入口全拒），已钳制为 1；"
                            + "在线配置路径有 min=1 校验，请检查 RL_* 环境变量", routeId, limit);
        }
        return 1;
    }
}
