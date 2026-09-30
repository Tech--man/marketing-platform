package com.example.marketing.gateway.filter;

import com.example.marketing.common.config.ConfigValues;
import com.example.marketing.gateway.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 鉴权<b>之前</b>的粗粒度限流（W11，2026-09-29 审查收口）。
 *
 * <p>为什么需要它：RateLimitFilter 的 order 是 -50，而两个鉴权 filter 在
 * -110/-105 之前——不带凭证刷 {@code /api/seckill/*}、{@code /api/admin/*} 的
 * 垃圾流量在被 401 拒掉时<b>不消耗任何限流桶</b>，只花 HMAC 验签成本。这道粗桶
 * 挂在 order -150（所有鉴权之前），按"全局 /api/** 每 IP"维度兜住这类洪水。</p>
 *
 * <p>粒度刻意粗（不分路由）：鉴权后的精细维度仍归 RateLimitFilter。Redis 异常
 * 时 fail-open（放行 + degraded 计数）——粗桶不能比它保护的入口还脆弱。</p>
 *
 * <p>阈值是<b>出厂值</b>，未经压测校准——上线前用 load-probe 实测全栈容量 X，
 * 把 {@code RL_PREAUTH} 调到 5X 左右（防洪水而非防用户）。</p>
 */
@Slf4j
@Component
public class PreAuthRateLimitFilter implements GlobalFilter, Ordered {

    private final GatewayProperties properties;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    @SuppressWarnings("rawtypes")
    private final RedisScript<Long> slidingWindowScript;

    public PreAuthRateLimitFilter(GatewayProperties properties,
                                  ReactiveRedisTemplate<String, String> redisTemplate,
                                  io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/sliding_window.lua"));
        script.setResultType(Long.class);
        this.slidingWindowScript = script;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        GatewayProperties.PreAuthRateLimit config = properties.getPreAuthRateLimit();
        if (!config.isEnabled() || !path.startsWith("/api/")) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest();
        String clientKey = request.getRemoteAddress() == null
                ? "unknown" : request.getRemoteAddress().getAddress().getHostAddress();
        String windowKey = "gw:rl:preauth:" + clientKey;
        long now = System.currentTimeMillis();

        List<String> keys = List.of(windowKey);
        List<String> args = List.of(
                String.valueOf(now),
                String.valueOf(config.getWindowSeconds() * 1000),
                String.valueOf(config.getLimit()),
                UUID.randomUUID().toString());

        return redisTemplate.execute(slidingWindowScript, keys, args)
                .next()
                .defaultIfEmpty(1L)
                .onErrorResume(e -> {
                    meterRegistry.counter("marketing.gateway.rate.limit.degraded", "stage", "preauth")
                            .increment();
                    log.warn("[preauth-rl] 计数不可用，本请求放行（fail-open）: {}", e.toString());
                    return Mono.just(1L);
                })
                .flatMap(allowed -> {
                    if (allowed == 1L) {
                        return chain.filter(exchange);
                    }
                    meterRegistry.counter("marketing.gateway.rate.limit.rejected",
                            "route", "preauth").increment();
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("code", 42900);
                    body.put("message", "请求过于频繁，请稍后再试");
                    body.put("data", null);
                    return GatewayResponses.writeJson(exchange.getResponse(), HttpStatus.TOO_MANY_REQUESTS, body);
                });
    }

    @Override
    public int getOrder() {
        // TraceIdGatewayFilter(-200) 之后、AdminAuthFilter(-110) 之前：
        // traceId 先有（拒绝日志可追），鉴权洪水先挡（不烧 HMAC）
        return -150;
    }
}
