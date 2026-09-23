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

    /**
     * 阻塞轮询器。{@code ConfigSyncer} 出现即让位：网关有自己的 reactive 同步器，
     * 而它的 classpath 上确实存在 StringRedisTemplate bean（见 {@link ConfigSyncer}）。
     *
     * <p>属性写成"属性 &gt; 环境变量 {@code DEPLOY_FORM} &gt; 空"的嵌套占位：这样七个进程的
     * 形态标识只有一处定义，不必在每个 application.yml 里复制同一段（本仓库曾专门消灭过
     * 这类 {@code marketing.*} 的等值副本）。</p>
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean({ConfigSnapshotPoller.class, ConfigSyncer.class})
    public ConfigSnapshotPoller configSnapshotPoller(StringRedisTemplate redis,
                                                     ConfigValues values,
                                                     ConfigSchemaRegistry registry,
                                                     MeterRegistry meters,
                                                     @Value("${marketing.config.form:${DEPLOY_FORM:}}") String form,
                                                     @Value("${spring.application.name:unknown}") String service,
                                                     @Value("${marketing.config.poll-seconds:${CONFIG_POLL_SECONDS:5}}")
                                                     long pollSeconds) {
        return new ConfigSnapshotPoller(redis, values, registry, form, service, pollSeconds, meters);
    }
}
