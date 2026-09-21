package com.example.marketing.common.config;

import com.example.marketing.common.idempotent.IdempotentExecutor;
import com.example.marketing.common.message.LocalMessageService;
import com.example.marketing.common.message.LocalMessageRetryer;
import com.example.marketing.common.mq.EventPublisher;
import com.example.marketing.common.mq.RedisStreamEventPublisher;
import com.example.marketing.common.mq.StreamConsumerRegistrar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * common 自动装配的条件矩阵（此前零覆盖）。
 *
 * <p>用 ApplicationContextRunner 而非真中间件：Lettuce 与 H2 都是"建 bean 不建连接"，
 * 因此这些断言在 {@code mvn test} 阶段就能跑，不依赖 compose 环境。</p>
 */
class MarketingCommonAutoConfigurationTest {

    /** 每个用例独立起一个 H2（内存库），只为满足 DataSource / JdbcTemplate 依赖 */
    private static EmbeddedDatabase memoryDatabase() {
        return new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).build();
    }

    /** 只满足 @ConditionalOnBean(StringRedisTemplate) 这个类型条件：端口 1 永不连接成功 */
    private static LettuceConnectionFactory neverConnectingFactory() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 1);
        factory.afterPropertiesSet();
        return factory;
    }

    private ApplicationContextRunner runner(DataSource dataSource, LettuceConnectionFactory factory) {
        return new ApplicationContextRunner()
                .withBean(DataSource.class, () -> dataSource)
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(factory))
                .withConfiguration(AutoConfigurations.of(MarketingCommonAutoConfiguration.class));
    }

    @Test
    @DisplayName("redis-stream 分支：装配 Stream 发布器与本地消息表链路")
    void redisStreamBranchWiresPublisherAndLocalMessage() {
        EmbeddedDatabase db = memoryDatabase();
        LettuceConnectionFactory factory = neverConnectingFactory();
        try {
            runner(db, factory).withPropertyValues("marketing.mq.type=redis-stream").run(ctx -> {
                assertTrue(ctx.containsBean("idempotentExecutor"), "有 DataSource 就该有幂等执行器");
                assertInstanceOf(RedisStreamEventPublisher.class, ctx.getBean(EventPublisher.class));
                assertTrue(ctx.getBeanNamesForType(LocalMessageService.class).length == 1,
                        "本地消息表应在分支内部装配");
                assertTrue(ctx.getBeanNamesForType(LocalMessageRetryer.class).length == 1);
                // 没有 StreamMessageHandler 时不应起消费容器（避免空轮询线程）
                assertFalse(ctx.getBeanNamesForType(StreamConsumerRegistrar.class).length > 0,
                        "无 handler 时不应装配 Stream 消费容器");
            });
        } finally {
            db.shutdown();
            factory.destroy();
        }
    }

    @Test
    @DisplayName("默认（rocketmq）分支：无 RocketMQTemplate 时不装 EventPublisher")
    void rocketMqBranchStaysOffWithoutTemplate() {
        EmbeddedDatabase db = memoryDatabase();
        LettuceConnectionFactory factory = neverConnectingFactory();
        try {
            runner(db, factory).run(ctx -> {
                assertFalse(ctx.getBeanNamesForType(EventPublisher.class).length > 0,
                        "没有 RocketMQTemplate 就不应装 rocketmq 发布器");
                assertFalse(ctx.getBeanNamesForType(LocalMessageService.class).length > 0);
                // 幂等执行器属于顶层无条件分支，仍应存在
                assertTrue(ctx.getBeanNamesForType(IdempotentExecutor.class).length == 1);
            });
        } finally {
            db.shutdown();
            factory.destroy();
        }
    }

    @Test
    @DisplayName("无 DataSource 时整个自动装配不生效（条件互不牵连）")
    void backsOffWithoutDataSource() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MarketingCommonAutoConfiguration.class))
                .run(ctx -> assertFalse(ctx.getBeanNamesForType(IdempotentExecutor.class).length > 0,
                        "没有 DataSource 时不应装配幂等执行器"));
    }
}
