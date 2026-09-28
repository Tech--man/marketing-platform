package com.example.marketing.gateway.config;

import com.example.marketing.common.security.ConsumerTokenCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 网关侧的消费者验签件。
 *
 * <p>与 {@link AdminGatewayConfig} 同一取舍：<b>密钥留空也照样建 bean</b>，
 * 让网关能起来。留空的后果由 ConsumerAuthFilter 落成"受保护的身份端点整片 40300"，
 * 而不是网关启动失败 —— 网关上还挂着后台与静态资源，不该因为一个分区的密钥漏配
 * 就把整个入口拖下线。</p>
 */
@Configuration
public class ConsumerGatewayConfig {

    @Bean
    public ConsumerTokenCodec consumerTokenCodec(GatewayProperties properties) {
        return new ConsumerTokenCodec(properties.getConsumer().getJwtSecret(),
                Duration.ofSeconds(properties.getConsumer().getClockSkewSeconds()));
    }
}
