package com.example.marketing.admin.config;

import com.example.marketing.admin.observe.LocalMeterSource;
import com.example.marketing.admin.observe.OpsTargets;
import com.example.marketing.admin.observe.ProxyMeterSource;
import com.example.marketing.admin.observe.StreamDepth;
import com.example.marketing.admin.observe.TargetRef;
import com.example.marketing.admin.observe.TargetSources;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

/**
 * ④ 的装配。独立成一个 config 而不是塞进 {@link AdminSecurityConfig}：安全件与观测件的
 * 变更节奏、失败后果都不同，混在一起会让"只读面能不能起"绑在密钥校验那条路径上。
 *
 * <p>两个指标源<b>都建</b>，按 target 名分派（理由见 {@link OpsProperties}）：
 * 二选一会让 LITE 读不到网关的指标，而网关在每种形态下都是独立进程（母版事实 #2）。</p>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpsProperties.class)
public class OpsObservabilityConfig {

    /**
     * 快照时刻的来源。Spring Boot 不默认给 {@code Clock} bean，少了这一个，
     * standalone 会在装配 {@code OpsSnapshotService} 时直接起不来——
     * 单测注入的是固定时钟，看不见这种缺 bean 的失败（⑤ 有同款教训）。
     */
    @Bean
    public java.time.Clock opsClock() {
        return java.time.Clock.systemUTC();
    }

    /** target 名 → 已校验的抓取地址。校验只在这一处发生，请求期不再接受任何外部 host */
    @Bean
    public Map<String, TargetRef> opsTargets(OpsProperties properties) {
        Map<String, TargetRef> parsed = OpsTargets.parseAll(properties.getTargets());
        log.info("[ops] 可抓 target: {}", parsed.keySet());
        return parsed;
    }

    /** 分派器持有的正是两个具体源；按具体类型 new，不注册成两个 MetricSource bean 让注入歧义 */
    @Bean
    public TargetSources opsTargetSources(MeterRegistry registry, Map<String, TargetRef> opsTargets,
                                          OpsProperties properties) {
        return new TargetSources(new LocalMeterSource(registry), new ProxyMeterSource(opsTargets),
                properties.getLocalTargets());
    }

    /**
     * 队列深度读数。{@code StreamDepth} 刻意不是扫描出来的 bean：它要的是
     * "本形态走哪条消息通道"与"本进程注册了哪些 reheat type"，在这里显式喂进去。
     *
     * <p>通道判据复用 <b>{@code marketing.mq.type} 这个属性本身</b>而不是新加一个 ④ 专属开关：
     * standalone 的 yml 里它就是 {@code ${MQ_TYPE:redis-stream}}（LITE/dev 因此是 stream），
     * 分进程的 admin 没有这个属性、默认 {@code rocketmq}（FULL 因此 broker 深度不可见）。</p>
     */
    @Bean
    public StreamDepth opsStreamDepth(StringRedisTemplate redis,
                                      com.example.marketing.common.cache.CacheReheatRegistry reheatRegistry,
                                      @Value("${marketing.mq.type:rocketmq}") String mqType) {
        return new StreamDepth(redis, "redis-stream".equalsIgnoreCase(mqType), reheatRegistry.types());
    }
}
