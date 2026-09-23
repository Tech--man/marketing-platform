package com.example.marketing.gateway.config;

import com.example.marketing.common.config.ConfigDefinition;
import com.example.marketing.common.config.ConfigDefinitionProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 网关层限流阈值的在线自述。
 *
 * <p>③ 之后九条：四条 C 端 + 一条后台 + 四条业务后台（改预算/改库存这类低频写，
 * 与 admin-route 同档 50/s）。<b>清单必须与 yml 的 rate-limit map 逐字对齐</b>——
 * {@code GatewayConfigDefinitionsTest} 会因任何一侧漂移而红，这不是巧合而是 ⑤ 立的规矩：
 * 声明少了写不进（后台没这个输入框），多了则"写了没人消费"。</p>
 *
 * <p>出厂值就是 application.yml 里那九个 {@code RL_*} 的默认值（FULL 口径），LITE 的保守值由
 * compose 环境变量给（docker-compose.preview.yml）。两条路都留着：在线值只在被显式写过之后
 * 才盖住它们。</p>
 *
 * <p>下界取 1 而不是 0：0 等于"把入口关掉"，那不该是一个阈值字段的取值范围——真要关入口
 * 有下线动作，不该靠运营在输入框里填个 0。</p>
 */
@Component
public class GatewayConfigDefinitions implements ConfigDefinitionProvider {

    public static final String PREFIX = "gateway.ratelimit.";
    public static final String SUFFIX = ".limit";
    private static final long MAX_LIMIT = 200_000L;

    public static String keyOf(String routeId) {
        return PREFIX + routeId + SUFFIX;
    }

    @Override
    public String service() {
        return "marketing-gateway";
    }

    @Override
    public List<ConfigDefinition> definitions() {
        return List.of(
                ConfigDefinition.ofInt(keyOf("activity-route"), 500, 1, MAX_LIMIT, "活动路由每秒阈值"),
                ConfigDefinition.ofInt(keyOf("coupon-route"), 1000, 1, MAX_LIMIT, "领券路由每秒阈值"),
                ConfigDefinition.ofInt(keyOf("discount-route"), 2000, 1, MAX_LIMIT, "优惠计算路由每秒阈值"),
                ConfigDefinition.ofInt(keyOf("seckill-route"), 200, 1, MAX_LIMIT, "秒杀路由每秒阈值"),
                ConfigDefinition.ofInt(keyOf("admin-route"), 50, 1, MAX_LIMIT, "后台路由每秒阈值（受 BCrypt 成本约束）"),
                ConfigDefinition.ofInt(keyOf("admin-activity-route"), 50, 1, MAX_LIMIT, "活动管理写每秒阈值"),
                ConfigDefinition.ofInt(keyOf("admin-coupon-route"), 50, 1, MAX_LIMIT, "券模板管理写每秒阈值"),
                ConfigDefinition.ofInt(keyOf("admin-discount-route"), 50, 1, MAX_LIMIT, "优惠规则管理写每秒阈值"),
                ConfigDefinition.ofInt(keyOf("admin-seckill-route"), 50, 1, MAX_LIMIT, "秒杀活动管理写每秒阈值"));
    }
}
