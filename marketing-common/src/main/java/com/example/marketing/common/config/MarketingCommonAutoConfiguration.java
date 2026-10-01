package com.example.marketing.common.config;

import com.example.marketing.common.cache.CacheConsistency;
import com.example.marketing.common.cache.CacheConsistencyRegistry;
import com.example.marketing.common.cache.CacheReheatRegistry;
import com.example.marketing.common.cache.CacheReheater;
import com.example.marketing.common.exception.GlobalExceptionHandler;
import com.example.marketing.common.idempotent.IdempotentExecutor;
import com.example.marketing.common.message.LocalMessageRetryer;
import com.example.marketing.common.message.LocalMessageService;
import com.example.marketing.common.mq.EventPublisher;
import com.example.marketing.common.mq.RedisStreamEventPublisher;
import com.example.marketing.common.mq.RocketMqEventPublisher;
import com.example.marketing.common.mq.StreamConsumerRegistrar;
import com.example.marketing.common.mq.StreamMessageHandler;
import com.example.marketing.common.schedule.RedisLeaseLock;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import javax.sql.DataSource;

/**
 * marketing-common 自动装配：按依赖存在性条件化注册组件。
 *
 * <ul>
 *   <li>幂等执行器：需要 DataSource + JdbcTemplate；</li>
 *   <li>事件发布：marketing.mq.type=rocketmq（默认，需 RocketMQTemplate）或 redis-stream（Lite 形态，需 StringRedisTemplate）；</li>
 *   <li>本地消息表：存在 EventPublisher 时装配，与具体 MQ 实现无关；</li>
 *   <li>全局异常处理：仅 Servlet Web 环境（不影响网关 WebFlux）。</li>
 * </ul>
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration"
})
@ConditionalOnClass({DataSource.class, JdbcTemplate.class})
@ConditionalOnBean(DataSource.class)
public class MarketingCommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public IdempotentExecutor idempotentExecutor(
            JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Value(
                    "${marketing.idempotent.processing-lease-seconds:120}") long processingLeaseSeconds) {
        return new IdempotentExecutor(jdbcTemplate, processingLeaseSeconds);
    }

    /**
     * 启动期 schema 迁移断言（2026-10-01 审计 P1-1）：新代码硬依赖迁移列
     * （claim_token / source_id），未迁移卷上线的第一批写请求会全线 Unknown column——
     * 这里把它提前成启动失败。各服务在 yml 里声明自己需要的列，声明为空零开销放行。
     */
    @Bean
    @ConditionalOnMissingBean
    public com.example.marketing.common.schema.SchemaMigrationGuard schemaMigrationGuard(
            JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Value(
                    "${marketing.schema-guard.required-columns:}") List<String> requiredColumns) {
        return new com.example.marketing.common.schema.SchemaMigrationGuard(jdbcTemplate, requiredColumns);
    }

    /**
     * JVM 时区一致性告警（P2-2 进程侧兜底）：与 serverTimezone 期望不一致时启动即 WARN，
     * 防"绕过入口脚本裸起 JVM"的形态把到期/超时判定整体偏移成静默错误。只告警不拦启动。
     */
    @Bean
    @ConditionalOnMissingBean
    public com.example.marketing.common.schema.TimezoneGuard timezoneGuard(
            @org.springframework.beans.factory.annotation.Value(
                    "${marketing.timezone-expected:Asia/Shanghai}") String expectedZoneId) {
        return new com.example.marketing.common.schema.TimezoneGuard(expectedZoneId);
    }

    /**
     * 缓存重预热注册表：收集各业务模块自己的 {@link CacheReheater}，按 type 分发。
     *
     * <p>公式留在领域里（预算在 activity、券在 coupon、桶在 seckill），这里只做装配与分发，
     * 管理后台的运维入口（后续段）直接注入它即可，不必了解任何一处的算法。
     * 两个模块声明同一个 type 会在启动期直接失败，而不是运行时互相覆盖。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public CacheReheatRegistry cacheReheatRegistry(ObjectProvider<CacheReheater> reheaters) {
        return new CacheReheatRegistry(reheaters.orderedStream().toList());
    }

    /**
     * 跨进程重预热的执行侧（③ T8）：admin 把"刷这个键"投进
     * {@code mkt:reheat:{type}:pending}，拥有该 type 的进程取走执行并写回执。
     *
     * <p>注册表为空的进程（admin、网关）{@code start()} 会直接返回，不起线程 ——
     * 比"起了但每轮都什么都不做"好：后者会让"为什么没人执行"变成一次线程排查。</p>
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean
    public com.example.marketing.common.reheat.ReheatDispatcher reheatDispatcher(
            StringRedisTemplate stringRedisTemplate, CacheReheatRegistry registry, MeterRegistry meterRegistry,
            @org.springframework.beans.factory.annotation.Value(
                    "${marketing.reheat.poll-seconds:${CONFIG_POLL_SECONDS:5}}") long pollSeconds) {
        return new com.example.marketing.common.reheat.ReheatDispatcher(
                stringRedisTemplate, registry, meterRegistry, pollSeconds);
    }

    /**
     * 缓存一致性自检注册表：把各模块的 {@link com.example.marketing.common.cache.CacheConsistency}
     * 绑成 gauge，供 ④ 的只读面取用。
     *
     * <p>与 {@link CacheReheatRegistry} 分开而不是并成一个接口：修（reheat）与发现（自检）
     * 的失败后果不同——reheat 抛错是运营的动作失败，自检抛错只该让那一项读成"判定不了"。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public CacheConsistencyRegistry cacheConsistencyRegistry(
            ObjectProvider<CacheConsistency> checks, MeterRegistry meterRegistry) {
        return new CacheConsistencyRegistry(checks.orderedStream().toList(), meterRegistry);
    }

    /**
     * 周期任务去重锁：一个调度周期内只让一个实例执行补偿/回补/过期扫描。
     * 正确性仍由业务侧幂等与状态 CAS 兜底，这把锁只负责不重复干活、不重复投消息。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(StringRedisTemplate.class)
    public RedisLeaseLock redisLeaseLock(StringRedisTemplate stringRedisTemplate, MeterRegistry meterRegistry) {
        return new RedisLeaseLock(stringRedisTemplate, meterRegistry);
    }

    /**
     * Servlet Web 环境下的全局异常处理。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestControllerAdvice")
    static class WebExceptionConfiguration {
        /**
         * 管理写失败留痕（P2-10）需要 AuditOutbox 与 AdminRequestIdentity——两者都在
         * AdminSecurityAutoConfiguration 里装配，这里用 ObjectProvider 软引用：
         * 缺了（如 admin 模块没装 Redis 的假想形态）异常处理器照常工作，只是不留痕。
         */
        @Bean
        @ConditionalOnMissingBean
        public GlobalExceptionHandler globalExceptionHandler(
                ObjectProvider<com.example.marketing.common.audit.AuditOutbox> auditOutbox,
                ObjectProvider<com.example.marketing.common.security.AdminRequestIdentity> adminIdentity) {
            return new GlobalExceptionHandler(auditOutbox.getIfAvailable(), adminIdentity.getIfAvailable());
        }
    }

    /**
     * traceId 的 Servlet 侧挂点（W12，2026-09-29 审查收口）：网关入口生成
     * X-Trace-Id 并透传，这里放进 MDC 供日志 pattern 印 {@code %X{traceId}}。
     * 注册成 FilterRegistrationBean 而不是裸 @Bean：要抢在最前（order 最高），
     * 否则业务日志先打出去时 MDC 还没填。
     */
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    static class TraceIdConfiguration {
        @Bean
        public org.springframework.boot.web.servlet.FilterRegistrationBean<
                com.example.marketing.common.web.TraceIdFilter> traceIdFilter() {
            org.springframework.boot.web.servlet.FilterRegistrationBean<
                    com.example.marketing.common.web.TraceIdFilter> registration =
                    new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                            new com.example.marketing.common.web.TraceIdFilter());
            registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10);
            return registration;
        }
    }

    /**
     * 事件发布：装配方式按 marketing.mq.type 选型，两个分支条件互斥。
     *
     * <p>注意：LocalMessageService 的装配必须在各分支内部完成，不能用
     * {@code @ConditionalOnBean(EventPublisher)}——同一 @AutoConfiguration 的
     * @Bean 方法定义在 parse 阶段结束后才注册，自身成员类条件评估时看不到它。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RocketMQTemplate.class)
    @ConditionalOnBean(RocketMQTemplate.class)
    @ConditionalOnProperty(name = "marketing.mq.type", havingValue = "rocketmq", matchIfMissing = true)
    static class RocketMqPublisherConfiguration {
        @Bean
        public EventPublisher rocketMqEventPublisher(RocketMQTemplate rocketMQTemplate) {
            return new RocketMqEventPublisher(rocketMQTemplate);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "marketing.message", name = "enabled", matchIfMissing = true)
        public LocalMessageService localMessageService(JdbcTemplate jdbcTemplate, EventPublisher eventPublisher) {
            return new LocalMessageService(jdbcTemplate, eventPublisher);
        }

        @Bean
        @ConditionalOnMissingBean
        public LocalMessageRetryer localMessageRetryer(LocalMessageService localMessageService,
                RedisLeaseLock leaseLock, MeterRegistry meterRegistry,
                @Value("${marketing.message.retry-interval-ms:10000}") long retryIntervalMs,
                @Value("${marketing.idempotent.retention-days:30}") int idempotentRetentionDays) {
            return new LocalMessageRetryer(localMessageService, leaseLock, meterRegistry,
                    retryIntervalMs, idempotentRetentionDays);
        }
    }

    /**
     * 事件发布 + 消费容器 + 本地消息表：Lite 形态（marketing.mq.type=redis-stream）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnProperty(name = "marketing.mq.type", havingValue = "redis-stream")
    static class RedisStreamMqConfiguration {
        @Bean
        public EventPublisher redisStreamEventPublisher(StringRedisTemplate stringRedisTemplate) {
            return new RedisStreamEventPublisher(stringRedisTemplate);
        }

        @Bean
        @ConditionalOnBean(StreamMessageHandler.class)
        public StreamConsumerRegistrar streamConsumerRegistrar(StringRedisTemplate stringRedisTemplate,
                List<StreamMessageHandler> handlers,
                @Value("${marketing.mq.stream-concurrency:8}") int concurrency) {
            return new StreamConsumerRegistrar(stringRedisTemplate, handlers, concurrency);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "marketing.message", name = "enabled", matchIfMissing = true)
        public LocalMessageService localMessageService(JdbcTemplate jdbcTemplate, EventPublisher eventPublisher) {
            return new LocalMessageService(jdbcTemplate, eventPublisher);
        }

        @Bean
        @ConditionalOnMissingBean
        public LocalMessageRetryer localMessageRetryer(LocalMessageService localMessageService,
                RedisLeaseLock leaseLock, MeterRegistry meterRegistry,
                @Value("${marketing.message.retry-interval-ms:10000}") long retryIntervalMs,
                @Value("${marketing.idempotent.retention-days:30}") int idempotentRetentionDays) {
            return new LocalMessageRetryer(localMessageService, leaseLock, meterRegistry,
                    retryIntervalMs, idempotentRetentionDays);
        }
    }
}
