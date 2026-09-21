package com.example.marketing.common.config;

import com.example.marketing.common.exception.GlobalExceptionHandler;
import com.example.marketing.common.idempotent.IdempotentExecutor;
import com.example.marketing.common.message.LocalMessageRetryer;
import com.example.marketing.common.message.LocalMessageService;
import com.example.marketing.common.mq.EventPublisher;
import com.example.marketing.common.mq.RedisStreamEventPublisher;
import com.example.marketing.common.mq.RocketMqEventPublisher;
import com.example.marketing.common.mq.StreamConsumerRegistrar;
import com.example.marketing.common.mq.StreamMessageHandler;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
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
    public IdempotentExecutor idempotentExecutor(JdbcTemplate jdbcTemplate) {
        return new IdempotentExecutor(jdbcTemplate);
    }

    /**
     * Servlet Web 环境下的全局异常处理。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestControllerAdvice")
    static class WebExceptionConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
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
        public LocalMessageRetryer localMessageRetryer(LocalMessageService localMessageService) {
            return new LocalMessageRetryer(localMessageService);
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
        public LocalMessageRetryer localMessageRetryer(LocalMessageService localMessageService) {
            return new LocalMessageRetryer(localMessageService);
        }
    }
}
