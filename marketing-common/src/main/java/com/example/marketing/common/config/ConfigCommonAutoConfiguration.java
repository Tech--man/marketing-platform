package com.example.marketing.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 在线配置的读取侧装配。
 *
 * <p><b>为什么不塞进 {@code MarketingCommonAutoConfiguration}</b>：那个类整体挂着
 * {@code @ConditionalOnBean(DataSource)}，而网关没有库。塞进去的净结果是
 * "在线限流恰好在最需要它的进程里不生效"。</p>
 *
 * <p>本类只条件依赖 micrometer 与（可选）StringRedisTemplate。网关只有 reactive 模板，
 * 于是它的轮询件在网关模块自己实现，这里自然不装阻塞线程。</p>
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
@ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
public class ConfigCommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConfigSchemaRegistry configSchemaRegistry(ObjectProvider<ConfigDefinitionProvider> providers) {
        return new ConfigSchemaRegistry(providers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfigValues configValues(ConfigSchemaRegistry registry, MeterRegistry meters) {
        return new ConfigValues(registry, meters);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean
    @ConditionalOnBean(StringRedisTemplate.class)
    public ConfigSnapshotPoller configSnapshotPoller(StringRedisTemplate redis,
                                                     ConfigValues values,
                                                     ConfigSchemaRegistry registry,
                                                     MeterRegistry meters,
                                                     @Value("${marketing.config.form:}") String form,
                                                     @Value("${spring.application.name:unknown}") String service,
                                                     @Value("${marketing.config.poll-seconds:5}") long pollSeconds) {
        return new ConfigSnapshotPoller(redis, values, registry, form, service, pollSeconds, meters);
    }
}
