package com.example.marketing.admin.config;

import com.example.marketing.common.security.AdminTokenCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;

/**
 * 后台安全件装配。BCrypt 强度用默认 10：单次校验 50-100ms，只出现在登录口，
 * 不在 C 端热路径上。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AdminProperties.class)
public class AdminSecurityConfig {

    static final String DEV_SECRET_PLACEHOLDER = "dev-only-secret-change-me";

    @Bean
    public AdminTokenCodec adminTokenCodec(AdminProperties properties) {
        String secret = properties.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "marketing.admin.jwt-secret 未配置：后台 token 无法签发，请设 ADMIN_JWT_SECRET（需与网关同值）");
        }
        if (DEV_SECRET_PLACEHOLDER.equals(secret)) {
            log.warn("[admin] 正在使用 dev 档占位 JWT 密钥，任何拿到仓库的人都可自签管理员 token —— 正式/预览环境必须换掉 ADMIN_JWT_SECRET");
        }
        return new AdminTokenCodec(secret, Duration.ofSeconds(properties.getClockSkewSeconds()));
    }

    @Bean
    public PasswordEncoder adminPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
