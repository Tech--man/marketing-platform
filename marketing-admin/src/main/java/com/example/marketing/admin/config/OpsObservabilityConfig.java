package com.example.marketing.admin.config;

import com.example.marketing.admin.observe.LocalMeterSource;
import com.example.marketing.admin.observe.MetricSource;
import com.example.marketing.admin.observe.OpsTargets;
import com.example.marketing.admin.observe.ProxyMeterSource;
import com.example.marketing.admin.observe.TargetRef;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * ④ 的装配。独立成一个 config 而不是塞进 {@link AdminSecurityConfig}：安全件与观测件的
 * 变更节奏、失败后果都不同，混在一起会让"只读面能不能起"绑在密钥校验那条路径上。
 *
 * <p>两个 {@link MetricSource} 按 {@code metrics-mode} <b>二选一</b>，不是都装然后挑：
 * LITE 下若同时装了 proxy，它会去抓 8081-8084 那几个本不存在的进程，大盘上永远挂着四条
 * error——而"某个 target 抓不到"正是 ④ 要人当回事的信号，不能给它做背景噪音。</p>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpsProperties.class)
public class OpsObservabilityConfig {

    @Bean
    @ConditionalOnProperty(prefix = "marketing.admin.ops", name = "metrics-mode",
            havingValue = OpsProperties.MODE_PROXY, matchIfMissing = true)
    public MetricSource proxyMeterSource(OpsProperties properties) {
        Map<String, TargetRef> targets = OpsTargets.parseAll(properties.getTargets());
        if (targets.isEmpty()) {
            throw new IllegalStateException("marketing.admin.ops.metrics-mode=proxy 但 targets 为空："
                    + "整个只读面会静默变成\"什么都读不到\"。LITE/dev 请把 mode 设成 local，"
                    + "FULL 请下发 marketing.admin.ops.targets.*");
        }
        log.info("[ops] 跨进程抓取 {} 个 target: {}", targets.size(), targets.keySet());
        return new ProxyMeterSource(targets);
    }

    @Bean
    @ConditionalOnProperty(prefix = "marketing.admin.ops", name = "metrics-mode",
            havingValue = OpsProperties.MODE_LOCAL)
    public MetricSource localMeterSource(MeterRegistry registry) {
        if (!(registry instanceof PrometheusMeterRegistry)) {
            // 起得来但每次读都抛：与其让第一次点大盘的人看到"抓不到"，不如现在就喊
            log.warn("[ops] metrics-mode=local 但 MeterRegistry 不是 prometheus 实现（{}），"
                    + "本进程的指标读不出样本", registry.getClass().getName());
        }
        return new LocalMeterSource(registry);
    }
}
