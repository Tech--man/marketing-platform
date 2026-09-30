package com.example.marketing.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 回归（2026-09-30 第二轮复审）：/ui/../actuator/** 路径穿越。
 *
 * <p>网关不归一化路径 + /ui/** 谓词把 {@code ..} 当普通段 + 下游 Tomcat 归一化
 * = 未鉴权抵达 admin/standalone 同端口的 actuator。这道闸在一切 path 判定之前
 * 拒绝穿越形状。</p>
 */
class PathNormalizeGuardFilterTest {

    private final GatewayFilterChain chain = mock(GatewayFilterChain.class);
    private final PathNormalizeGuardFilter filter = new PathNormalizeGuardFilter();

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private MockServerWebExchange exchange(String path) {
        // MockServerHttpRequest 的 URI 直接解析：%2e 等编码由 URI.getPath() 解码
        return MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, URI.create("http://gw" + path)));
    }

    @Test
    @DisplayName("/ui/../actuator/prometheus（穿越到 admin 同端口 actuator）→ 400，绝不进链")
    void traversalToActuatorRejected() {
        MockServerWebExchange ex = exchange("/ui/../actuator/prometheus");

        filter.filter(ex, chain).block();

        assertEquals(400, ex.getResponse().getStatusCode().value());
        verify(chain, times(0)).filter(any());
    }

    @Test
    @DisplayName("编码变体 %2e%2e 同样被拒（守卫检查的是解码后的形状）")
    void encodedTraversalRejected() {
        MockServerWebExchange ex = exchange("/h5/%2e%2e/actuator/env");

        filter.filter(ex, chain).block();

        assertEquals(400, ex.getResponse().getStatusCode().value());
        verify(chain, times(0)).filter(any());
    }

    @Test
    @DisplayName("反斜杠形状（%5c）被拒")
    void backslashRejected() {
        MockServerWebExchange ex = exchange("/ui/..%5c/actuator/health");

        filter.filter(ex, chain).block();

        assertEquals(400, ex.getResponse().getStatusCode().value());
    }

    @Test
    @DisplayName("正常路径（含静态资源、API）原样放行；文件名里的连续点不是穿越段")
    void normalPathsPass() {
        for (String p : new String[]{
                "/ui/assets/index-D8GfgdjR.js",
                "/h5/",
                "/api/coupon/grant",
                "/api/admin/activities/ACT2026001/gray"}) {
            MockServerWebExchange ex = exchange(p);
            filter.filter(ex, chain).block();
        }
        verify(chain, times(4)).filter(any());
        // 文件名里出现连续点不是路径段（如 index..js）
        assertFalse(PathNormalizeGuardFilter.hasTraversal("/ui/assets/index..js"));
        assertTrue(PathNormalizeGuardFilter.hasTraversal("/ui/../actuator"));
        assertTrue(PathNormalizeGuardFilter.hasTraversal("/api/./admin"));
        assertTrue(PathNormalizeGuardFilter.hasTraversal("/ui/a/.."));
        assertFalse(PathNormalizeGuardFilter.hasTraversal("/api/v1..x/ok"));
    }
}
