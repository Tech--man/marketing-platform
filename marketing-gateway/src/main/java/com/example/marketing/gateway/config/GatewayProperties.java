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

    /** 鉴权配置 */
    private Auth auth = new Auth();

    /** 管理后台凭证分区配置 */
    private Admin admin = new Admin();

    /** 限流规则：key = 路由 ID，未配置的路由不限流 */
    private Map<String, RateRule> rateLimit = new HashMap<>();

    /** 免鉴权路径前缀 */
    private List<String> whitelist = new ArrayList<>();

    @Data
    public static class Auth {
        /** 演示级静态 Token（生产替换为 JWT/OAuth2 校验） */
        private String token = "demo-token-123";
        private boolean enabled = true;
    }

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
}
