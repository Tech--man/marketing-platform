package com.example.marketing.common.config;

import com.example.marketing.common.audit.AuditOutbox;
import com.example.marketing.common.security.AdminRequestIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * ③ 的后台身份件与审计投递口。
 *
 * <p>{@code afterName} 不是装饰：{@code auditOutbox} 条件于 {@code StringRedisTemplate}，
 * 而那个 bean 由 {@code RedisAutoConfiguration} 提供。同一 @AutoConfiguration 的成员
 * 条件评估时看不到别的自动配置稍后才注册的 bean —— 少了这行，真实启动里
 * {@code AuditOutbox} 永远缺席（LITE 实测：standalone 直接起不来）。</p>
 *
 * <p><b>为什么不塞进 {@code MarketingCommonAutoConfiguration}</b>：那个类整体挂着
 * {@code @ConditionalOnBean(DataSource)}，与 ⑤ 的教训同源——塞进去的净结果就是
 * "该装的进程没装上"。</p>
 *
 * <p>只条件于 Servlet：网关是 WebFlux，它既不需要业务侧验签件（它自己就是验签那一侧），
 * 更不该被一个 {@code HttpServletRequest} 类型的 Bean 拖进阻塞语义。</p>
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AdminSecurityAutoConfiguration {

    /**
     * 属性写成"属性 &gt; 环境变量"的嵌套占位，与 ⑤ 的 {@code marketing.config.form} 同一手法：
     * 六个进程共享一份定义，不必在各 application.yml 里复制 {@code marketing.admin.*}
     * （本仓库曾专门消灭过这类 {@code marketing.*} 的等值副本）。
     */
    @Bean
    @ConditionalOnMissingBean
    public AdminRequestIdentity adminRequestIdentity(
            @Value("${marketing.admin.token-secret:${ADMIN_JWT_SECRET:}}") String secret,
            @Value("${marketing.admin.token-clock-skew-seconds:30}") long skewSeconds) {
        return new AdminRequestIdentity(secret, Duration.ofSeconds(skewSeconds));
    }

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean
    public AuditOutbox auditOutbox(StringRedisTemplate redis, MeterRegistry meters) {
        return new AuditOutbox(redis, meters);
    }
}
