package com.example.marketing.gateway.filter;

import com.example.marketing.gateway.config.GatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;


import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C 端鉴权在两套凭证分区后的行为：对后台判定过的请求让路，其余一切照旧。
 * 重点是"照旧"—— 加后台不该把 C 端的门也一起改掉。
 */
class AuthFilterTest {

    private final GatewayProperties properties = new GatewayProperties();
    private final AuthFilter filter = new AuthFilter(properties);

    private GatewayFilterChain chain() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        return chain;
    }

    private MockServerWebExchange exchange(String path, String authorization) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("http://gw" + path);
        if (authorization != null) {
            builder = builder.header("Authorization", authorization);
        }
        return MockServerWebExchange.from(builder);
    }

    @Test
    @DisplayName("后台已判定过的请求直接让路，即使没带 C 端 token")
    void skipsWhenAdminAlreadyVerified() {
        GatewayFilterChain chain = chain();
        MockServerWebExchange exchange = exchange("/api/admin/users", null);
        exchange.getAttributes().put(AdminAuthFilter.VERIFIED, Boolean.TRUE);

        filter.filter(exchange, chain).block();

        verify(chain, times(1)).filter(any());
    }

    @Test
    @DisplayName("C 端路径行为不变：demo token 放行、乱串拒绝")
    void cSideBehaviourUnchanged() {
        GatewayFilterChain chain = chain();
        filter.filter(exchange("/api/activity/ACT2026001", "Bearer " + properties.getAuth().getToken()),
                chain).block();
        verify(chain, times(1)).filter(any());

        MockServerWebExchange bad = exchange("/api/activity/ACT2026001", "Bearer nope");
        filter.filter(bad, chain).block();
        verify(chain, times(1)).filter(any());
        assertTrue(responseBody(bad).contains("40100"));
    }

    @Test
    @DisplayName("没有 VERIFIED 标记的后台路径仍然要过 C 端鉴权（filter 顺序保证它拿得到标记）")
    void adminPathWithoutMarkerStillFallsBackToCSideAuth() {
        GatewayFilterChain chain = chain();
        MockServerWebExchange exchange = exchange("/api/admin/users", null);

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertTrue(responseBody(exchange).contains("40100"));
    }

    @Test
    @DisplayName("白名单仍然免鉴权")
    void whitelistStillBypasses() {
        // 白名单是 yml 配的，POJO 默认是空表 —— 测试里自己加，别依赖配置文件
        properties.getWhitelist().add("/actuator/**");
        GatewayFilterChain chain = chain();

        filter.filter(exchange("/actuator/health", null), chain).block();

        verify(chain, times(1)).filter(any());
    }

    private String responseBody(MockServerWebExchange exchange) {
        String body = exchange.getResponse().getBodyAsString().block();
        return body == null ? "" : body;
    }
}
