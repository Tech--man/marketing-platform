package com.example.marketing.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关配置：鉴权 token、白名单路径、按路由的限流规则。
 *
 * <p>bean 名显式指定，避免与 Spring Cloud Gateway 内置的 gatewayProperties bean 同名冲突。</p>
 */
@Data
@Component("marketingGatewayProperties")
@ConfigurationProperties(prefix = "marketing.gateway")
public class GatewayProperties {

    /** 管理后台凭证分区配置 */
    private Admin admin = new Admin();

    /** 消费者凭证分区配置 */
    private Consumer consumer = new Consumer();

    /** 限流规则：key = 路由 ID，未配置的路由不限流 */
    private Map<String, RateRule> rateLimit = new HashMap<>();

    /**
     * 后台 token 的校验参数。密钥留空不等于"放开后台"，而是"后台整片拒绝"——
     * 见 AdminAuthFilter：少配一个环境变量的后果必须落在后台身上，不能把 C 端流量一起带走。
     */
    @Data
    public static class Admin {
        /** HS256 密钥，必须与 admin 服务同值 */
        private String jwtSecret = "";
        /** 时钟偏移容忍（秒），跨主机 FULL 依赖 NTP */
        private long clockSkewSeconds = 30;
        /** 免后台凭证的路径（Ant 风格）：只有登录口，子路径特例不进 C 端白名单 */
        private List<String> permitPaths = new ArrayList<>(List.of("/api/admin/auth/login"));
    }

    @Data
    public static class RateRule {
        /** 窗口内允许的最大请求数 */
        private long limit = 100;
        /** 滑动窗口大小（秒） */
        private long windowSeconds = 1;
    }

    /**
     * 消费者 token 的校验参数。密钥留空的后果与后台同形且理由相同：
     * 受保护的身份端点整片拒绝（40300），而不是退化成"接受任何身份头"，
     * 也不把 C 端业务流量一起带走。
     */
    @Data
    public static class Consumer {
        /** HS256 密钥，必须与 account 服务同值，且必须与 ADMIN_JWT_SECRET 是两个值 */
        private String jwtSecret = "";
        /** 时钟偏移容忍（秒），跨主机 FULL 依赖 NTP */
        private long clockSkewSeconds = 30;
        /**
         * 归 ConsumerAuthFilter 管的路径（Ant 风格）。
         *
         * <p>默认是<b>整片 {@code /api/**}</b>，而不是"已知需要登录的那几条"。方向很重要：
         * 新增一条 {@code /api/xxx} 路由时的默认结果应该是"要求登录"，而不是
         * "谁都能调"。后台那半边靠 {@code AdminAuthFilter} 先留下的 VERIFIED 标记让开，
         * 不靠这里排除前缀。</p>
         */
        private List<String> managedPaths = new ArrayList<>(List.of("/api/**"));
        /**
         * 免 access token 的口。两条判据同时成立才进这张表：游客本来就该看到，
         * 且不落任何属于某个消费者的数据。
         */
        private List<String> permitPaths = new ArrayList<>(List.of(
                "/api/auth/login", "/api/auth/register", "/api/auth/refresh",
                "/api/activity/*",
                "/api/activity/*/participatable",
                "/api/activity/*/budget/remain",
                "/api/coupon/stock/*",
                "/api/seckill/activities",
                "/api/seckill/stock/*"));
    }
}
