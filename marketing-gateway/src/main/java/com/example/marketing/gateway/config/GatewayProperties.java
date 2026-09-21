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

    @Data
    public static class RateRule {
        /** 窗口内允许的最大请求数 */
        private long limit = 100;
        /** 滑动窗口大小（秒） */
        private long windowSeconds = 1;
    }
}
