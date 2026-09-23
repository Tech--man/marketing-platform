package com.example.marketing.gateway.config;

import com.example.marketing.common.config.ConfigDefinition;
import com.example.marketing.common.config.ConfigDefinitionProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 网关层限流阈值的在线自述。
 *
 * <p>出厂值就是 application.yml 里那五个 {@code RL_*} 的默认值（FULL 口径），LITE 的保守值由
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
                ConfigDefinition.ofInt(keyOf("admin-route"), 50, 1, MAX_LIMIT, "后台路由每秒阈值（受 BCrypt 成本约束）"));
    }
}
