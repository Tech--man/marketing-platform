package com.example.marketing.common.config;

import com.example.marketing.common.security.ConsumerRequestIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * 消费者身份件的业务侧装配。与 {@link AdminSecurityAutoConfiguration} 同样的三条约束：
 *
 * <ul>
 *   <li><b>只条件于 Servlet</b>：网关是 WebFlux，它自己就是验签那一侧，
 *       不该被一个 {@code HttpServletRequest} 类型的 bean 拖进阻塞语义；</li>
 *   <li><b>不塞进 MarketingCommonAutoConfiguration</b>：那个类整体挂着
 *       {@code @ConditionalOnBean(DataSource)}，塞进去的净结果是"该装的进程没装上"
 *       （⑤ 已经为此付过一次学费）；</li>
 *   <li><b>属性写成"属性 &gt; 环境变量"的嵌套占位</b>：六个进程共享一份定义，
 *       不在各 application.yml 里复制 {@code marketing.consumer.*} 的等值副本。</li>
 * </ul>
 *
 * <p><b>按服务显式开启</b>（{@code @ConditionalOnProperty}）：只有在自己的 yml 里写了
 * {@code marketing.consumer.token-secret} 的进程才会装这个 bean。少了这条门槛，
 * ①②③④⑥ 之后每个既有的 Servlet 服务上下文（含它们的单测）都会因为
 * "没配一个新环境变量"而拒绝启动 —— 那是把新功能的失败半径扩散到了不需要它的进程上。
 * 需要它的服务一旦声明了该属性却留空，构造器仍然抛错、进程拒绝启动：
 * 缺配的代价必须落在"这个进程起不来"，而不是悄悄退化成"接受任何身份头"。</p>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "marketing.consumer", name = "token-secret")
public class ConsumerSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConsumerRequestIdentity consumerRequestIdentity(
            @Value("${marketing.consumer.token-secret:${CONSUMER_JWT_SECRET:}}") String secret,
            @Value("${marketing.consumer.token-clock-skew-seconds:30}") long skewSeconds) {
        return new ConsumerRequestIdentity(secret, Duration.ofSeconds(skewSeconds));
    }
}
