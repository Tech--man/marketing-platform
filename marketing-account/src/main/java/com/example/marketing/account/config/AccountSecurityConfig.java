package com.example.marketing.account.config;

import com.example.marketing.common.security.ConsumerTokenCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;

/**
 * 消费者账号安全件装配。BCrypt 强度沿用默认 10，与后台一致 —— 换密钥不换算法，
 * 两边哈希格式相同，将来若要统一升级 cost 只有一处要动。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AccountProperties.class)
public class AccountSecurityConfig {

    static final String DEV_SECRET_PLACEHOLDER = "dev-only-consumer-secret-change-me";

    @Bean
    public ConsumerTokenCodec consumerTokenCodec(AccountProperties properties) {
        String secret = properties.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "marketing.account.jwt-secret 未配置：消费者 token 无法签发，请设 CONSUMER_JWT_SECRET（需与网关同值）");
        }
        if (DEV_SECRET_PLACEHOLDER.equals(secret)) {
            log.warn("[account] 正在使用 dev 档占位密钥，任何拿到仓库的人都可自签消费者 token —— 正式/预览环境必须换掉 CONSUMER_JWT_SECRET");
        }
        if (secret.equals(System.getenv("ADMIN_JWT_SECRET"))) {
            // 不拒绝启动：两把密钥相同是配置错误而不是漏洞本身，但"两套凭证不互通"
            // 从此只靠 claim 形状，值得在日志里留一条响的
            log.warn("[account] CONSUMER_JWT_SECRET 与 ADMIN_JWT_SECRET 同值：后台 token 与消费者 token 的隔离退化为 claim 形状");
        }
        return new ConsumerTokenCodec(secret, Duration.ofSeconds(properties.getClockSkewSeconds()));
    }

    /**
     * {@code @ConditionalOnMissingBean} 不是省事，是 LITE 能不能起来的问题：
     * standalone 把 admin 与 account 聚在同一个 JVM、同一个上下文里，而 admin 已经
     * 注册了一个 PasswordEncoder。再来一个，任何按类型注入 PasswordEncoder 的地方
     * （两个服务的登录服务都是）都会 NoUniqueBeanDefinitionException —— 服役档直接起不来。
     * FULL 形态 account 单独成进程时，这里正常建一个。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(PasswordEncoder.class)
    public PasswordEncoder consumerPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
