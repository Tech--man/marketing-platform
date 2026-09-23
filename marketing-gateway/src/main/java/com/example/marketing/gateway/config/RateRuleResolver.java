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

    public Outcome resolve(String routeId, GatewayProperties.RateRule ymlRule, ConfigValues values) {
        if (ymlRule == null) {
            // route 不在 rate-limit map 里 = 完全不限流。本段不改这条语义（只改"在 map 里时阈值从哪来"）。
            return new Outcome(null, false, false);
        }
        String key = GatewayConfigDefinitions.keyOf(routeId);
        if (!values.overridden(key)) {
            // 返回 yml 那个实例本身：不每请求新建对象，也绝不去改共享的 @ConfigurationProperties bean
            return new Outcome(ymlRule, false, values.degradedKeys().contains(key));
        }
        GatewayProperties.RateRule merged = new GatewayProperties.RateRule();
        merged.setLimit(values.intOr(key, (int) ymlRule.getLimit()));
        merged.setWindowSeconds(ymlRule.getWindowSeconds());
        return new Outcome(merged, true, false);
    }
}
