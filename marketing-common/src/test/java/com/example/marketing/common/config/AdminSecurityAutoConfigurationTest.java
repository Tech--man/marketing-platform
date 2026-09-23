package com.example.marketing.common.config;

import com.example.marketing.common.audit.AuditOutbox;
import com.example.marketing.common.security.AdminRequestIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 装配矩阵。三档都必须成立：
 * Servlet + 有密钥 → 装；Servlet + 缺密钥 → 启动失败（不做免鉴权降级）；
 * reactive（网关）→ 不装。用 {@code ApplicationContextRunner} 而不是真中间件，
 * 与 ⑤ 的 {@code ConfigCommonAutoConfigurationTest} 同一手法。
 */
class AdminSecurityAutoConfigurationTest {

    private final WebApplicationContextRunner servletRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AdminSecurityAutoConfiguration.class))
            .withBean(SimpleMeterRegistry.class)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));

    @Test
    @DisplayName("Servlet 环境 + 有密钥 → 装出身份件与审计投递口")
    void servletWithSecretRegistersBoth() {
        servletRunner.withPropertyValues("marketing.admin.token-secret=test-secret")
                .run(ctx -> {
                    assertNotNull(ctx.getBean(AdminRequestIdentity.class));
                    assertNotNull(ctx.getBean(AuditOutbox.class));
                });
    }

    @Test
    @DisplayName("缺密钥 → 上下文启动失败，而不是让后台写免鉴权")
    void missingSecretFailsStartup() {
        servletRunner.run(ctx -> {
            Throwable failure = ctx.getStartupFailure();
            assertNotNull(failure, "没有密钥必须启动失败");
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            assertTrue(String.valueOf(root.getMessage()).contains("ADMIN_JWT_SECRET"),
                    "报错要点名缺哪个变量: " + root.getMessage());
        });
    }

    @Test
    @DisplayName("真实自动配置顺序下（Redis 自动配置在后）AuditOutbox 仍然装得上")
    void registersOutboxWithRealRedisAutoConfigurationOrdering() {
        // 这里刻意不用手搓的 mock StringRedisTemplate：那样测不到"条件评估早于
        // RedisAutoConfiguration 注册 bean"这个真实顺序，LITE 启动失败就是这么来的。
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class,
                        AdminSecurityAutoConfiguration.class))
                .withBean(SimpleMeterRegistry.class)
                .withPropertyValues(
                        "marketing.admin.token-secret=test-secret",
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=1")   // 永不连接：只要 bean 定义在，条件就该成立
                .run(ctx -> {
                    assertNotNull(ctx.getBeanNamesForType(AuditOutbox.class).length == 1
                                    ? ctx.getBean(AuditOutbox.class) : null,
                            "afterName 丢了这条就会红：AuditOutbox 缺席，业务进程起不来");
                    assertNotNull(ctx.getBean(AdminRequestIdentity.class));
                });
    }

    @Test
    @DisplayName("reactive 上下文（网关）不装这两个件")
    void reactiveContextSkipsThem() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AdminSecurityAutoConfiguration.class))
                .withPropertyValues("marketing.admin.token-secret=test-secret")
                .run(ctx -> assertEquals(0, ctx.getBeanNamesForType(AdminRequestIdentity.class).length));
    }

    @Test
    @DisplayName("非 Web 上下文（纯单测跑的 runner）也不装")
    void plainContextSkipsThem() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AdminSecurityAutoConfiguration.class))
                .withPropertyValues("marketing.admin.token-secret=test-secret")
                .run(ctx -> assertEquals(0, ctx.getBeanNamesForType(AdminRequestIdentity.class).length));
    }
}
