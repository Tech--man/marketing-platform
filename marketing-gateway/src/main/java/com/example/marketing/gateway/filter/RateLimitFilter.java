package com.example.marketing.gateway.filter;

import com.example.marketing.common.config.ConfigValues;
import com.example.marketing.gateway.config.GatewayProperties;
import com.example.marketing.gateway.config.RateRuleResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
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
 * Redis+Lua 滑动窗口限流过滤器（网关层，分层限流第一层）。
 *
 * <p>按"路由 ID + 客户端 IP"维度限流。阈值来源优先级：在线快照
 * （{@code gateway.ratelimit.<route>.limit}）&gt; 本进程 yml（{@code RL_*} 环境变量）；
 * 快照里的值越界或键未声明时逐条退回出厂值并告警——限流配置写错的后果只能是"放行偏宽或偏紧"，
 * 绝不能变成网关拒绝服务。{@code window-seconds} 恒取 yml：窗口宽度不是运营该在线改的东西。</p>
 *
 * <p>秒杀路由配置更严格的阈值，超限返回 429 + 排队码（前端可引导至排队页）。</p>
 */
@Slf4j
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final GatewayProperties properties;
    private final RateRuleResolver ruleResolver;
    private final ConfigValues configValues;
    private final org.springframework.data.redis.core.ReactiveRedisTemplate<String, String> redisTemplate;
    @SuppressWarnings("rawtypes")
    private final RedisScript<Long> slidingWindowScript;

    public RateLimitFilter(GatewayProperties properties, RateRuleResolver ruleResolver,
                           ConfigValues configValues,
                           org.springframework.data.redis.core.ReactiveRedisTemplate<String, String> redisTemplate) {
        this.properties = properties;
        this.ruleResolver = ruleResolver;
        this.configValues = configValues;
        this.redisTemplate = redisTemplate;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/sliding_window.lua"));
        script.setResultType(Long.class);
        this.slidingWindowScript = script;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }
        RateRuleResolver.Outcome resolved = ruleResolver.resolve(
                route.getId(), properties.getRateLimit().get(route.getId()), configValues);
        if (resolved.rule() == null) {
            return chain.filter(exchange);
        }
        GatewayProperties.RateRule rule = resolved.rule();
        if (resolved.ignored()) {
            log.warn("[rate-limit] 在线值未被采纳，本请求退回 yml 出厂值 route={}, limit={}",
                    route.getId(), rule.getLimit());
        }
        ServerHttpRequest request = exchange.getRequest();
        String clientKey = resolveClientKey(request);
        String windowKey = "gw:rl:" + route.getId() + ":" + clientKey;
        long now = System.currentTimeMillis();

        List<String> keys = List.of(windowKey);
        List<String> args = List.of(
                String.valueOf(now),
                String.valueOf(rule.getWindowSeconds() * 1000),
                String.valueOf(rule.getLimit()),
                UUID.randomUUID().toString());

        return redisTemplate.execute(slidingWindowScript, keys, args)
                .next()
                .defaultIfEmpty(1L)
                .flatMap(allowed -> {
                    if (allowed == 1L) {
                        return chain.filter(exchange);
                    }
                    log.info("[rate-limit] 拦截 route={}, key={}", route.getId(), clientKey);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("code", 42900);
                    body.put("message", "请求过于频繁，请稍后再试");
                    // 排队码：秒杀场景前端凭此进入排队页轮询
                    body.put("data", Map.of("queueCode", "Q" + now));
                    return AuthFilter.writeJson(exchange.getResponse(), HttpStatus.TOO_MANY_REQUESTS, body);
                });
    }

    /** 客户端标识：优先 X-Forwarded-For 首段，回退远端地址（生产可换为用户/设备维度） */
    private String resolveClientKey(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddress() == null
                ? "unknown" : request.getRemoteAddress().getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        // 鉴权之后执行
        return -50;
    }
}
