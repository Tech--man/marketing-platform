package com.example.marketing.gateway.config;

import com.example.marketing.common.security.AdminTokenCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 网关侧的后台验签件。密钥留空也照常建 bean —— 空密钥签不出也验不过任何东西，
 * 而"缺配置就整个网关起不来"会把 C 端流量一起带走。真正的拒绝在 AdminAuthFilter 里，
 * 并且只对 /api/admin/** 生效。
 */
@Configuration
public class AdminGatewayConfig {

    @Bean
    public AdminTokenCodec adminTokenCodec(GatewayProperties properties) {
        return new AdminTokenCodec(properties.getAdmin().getJwtSecret(),
                Duration.ofSeconds(properties.getAdmin().getClockSkewSeconds()));
    }
}
