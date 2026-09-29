package com.example.marketing.gateway.filter;

import com.example.marketing.common.config.ConfigValues;
import com.example.marketing.gateway.config.GatewayProperties;
import com.example.marketing.gateway.config.RateRuleResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流拒绝的<b>可观测性</b>：既有实现只在拒绝分支打一条 log，
 * 于是"哪个路由正在被打爆"这个问题没有任何指标可答——
 * {@code http_server_requests{status="429"}} 没有 route 维度，救不了。
 *
 * <p>同时钉住"这次改动没碰判定"：429 的响应体、放行分支、以及"路由不在 map 里=完全不限流"
 * 这三件事必须逐字节保持原样（{@code RateRuleResolverTest} 里那条 unlimited 断言的口径）。</p>
 */
class RateLimitFilterTest {

    private final GatewayProperties properties = new GatewayProperties();
    private final RateRuleResolver ruleResolver = mock(RateRuleResolver.class);
    private final ReactiveRedisTemplate<String, String> redis = mock(ReactiveRedisTemplate.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private RateLimitFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(properties, ruleResolver, ConfigValues.empty(), redis, meters);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private MockServerWebExchange exchange() {
        // 自报 XFF + 真实对端并存：H3 之后限流键只认对端地址（见 spoofedXffIsNotTheRateKey）
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://gw/api/seckill/grab")
                        .remoteAddress(new java.net.InetSocketAddress("192.0.2.9", 50000))
                        .header("X-Forwarded-For", "10.1.2.3, 10.0.0.1"));
        Route route = Route.async().id("seckill-route").uri(URI.create("http://127.0.0.1:8084"))
                .predicate(s -> true).build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        return exchange;
    }

    private void ruleIsOnePerSecond() {
        GatewayProperties.RateRule rule = new GatewayProperties.RateRule();
        rule.setLimit(1);
        rule.setWindowSeconds(1);
        when(ruleResolver.resolve(any(), any(), any())).thenReturn(
                new RateRuleResolver.Outcome(rule, false, false));
    }

    private java.util.List<String> capturedKeys;

    @SuppressWarnings("unchecked")
    private void luaSays(long allowed) {
        // 顺带记录本次限流键，供 H3 用例断言"自报 XFF 不进键"
        when(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .thenAnswer(invocation -> {
                    capturedKeys = invocation.getArgument(1);
                    return Flux.just(allowed);
                });
    }

    @Test
    @DisplayName("被拒：429 + 排队码原样不变，并且按 route 记一计数")
    void rejectionIsCountedByRoute() {
        ruleIsOnePerSecond();
        luaSays(0L);
        MockServerWebExchange exchange = exchange();

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        String body = exchange.getResponse().getBodyAsString().block();
        assertTrue(body != null && body.contains("42900") && body.contains("queueCode"),
                "响应体形状不许漂: " + body);
        verify(chain, never()).filter(any());
        assertEquals(1.0, meters.counter("marketing.gateway.rate.limit.rejected",
                "route", "seckill-route").count());
    }

    @Test
    @DisplayName("放行不计数：不然这条指标读数的意义就反了")
    void allowedDoesNotCount() {
        ruleIsOnePerSecond();
        luaSays(1L);

        filter.filter(exchange(), chain).block();

        verify(chain).filter(any());
        assertEquals(0.0, meteredCount(), "放行也计数就等于没有这条指标");
    }

    @Test
    @DisplayName("路由不在限流 map 里 = 完全不限流（本段不改这条语义）")
    void routeWithoutRuleIsUnlimited() {
        when(ruleResolver.resolve(any(), any(), any())).thenReturn(
                new RateRuleResolver.Outcome(null, false, false));

        filter.filter(exchange(), chain).block();

        verify(chain).filter(any());
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyList());
        assertEquals(0.0, meteredCount());
    }

    @Test
    @DisplayName("根因 C：Redis 计数抛异常 → fail-open 放行并计 degraded，不再是入口 5xx")
    void redisFailureFailsOpen() {
        ruleIsOnePerSecond();
        when(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new RuntimeException("connection refused")));

        filter.filter(exchange(), chain).block();

        verify(chain).filter(any());
        assertEquals(1.0, meters.counter("marketing.gateway.rate.limit.degraded").count(),
                "降级必须有指标：洪流保护自己的可用性不能比后端还低");
    }

    @Test
    @DisplayName("H3：自报 XFF 不进限流键——键取 TCP 对端地址，换假 IP 刷不出新桶")
    void spoofedXffIsNotTheRateKey() {
        ruleIsOnePerSecond();
        luaSays(1L);

        filter.filter(exchange(), chain).block();

        assertEquals("gw:rl:seckill-route:192.0.2.9", capturedKeys.get(0),
                "限流键必须是对端地址，XFF 首段 10.1.2.3 出现在键里就是回归");
    }

    private double meteredCount() {
        var found = meters.find("marketing.gateway.rate.limit.rejected").counters();
        return found.stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }
}
