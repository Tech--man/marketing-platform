package com.example.marketing.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * traceId 的入口（W12，2026-09-29 审查收口）：网关是唯一对外入口，在这里生成
 * {@code X-Trace-Id}、剥掉客户端伪造的同名头、透传给所有上游并回写响应头。
 *
 * <p>下游 Servlet 服务由 common 的 {@code TraceIdFilter} 把它放进 MDC，
 * 日志 pattern 印 {@code %X{traceId}} 后跨"网关→业务"的排障就有了串起来的钥匙。</p>
 *
 * <p>order 抢在两个鉴权 filter（-110/-105）之前：鉴权拒绝的日志也该带 traceId。</p>
 */
@Component
public class TraceIdGatewayFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(headers -> {
                    headers.remove(HEADER);
                    headers.set(HEADER, traceId);
                }))
                .build();
        // 回写响应头：前端/调用方报障时能把 traceId 一并贴出来
        HttpHeaders responseHeaders = mutated.getResponse().getHeaders();
        responseHeaders.set(HEADER, traceId);
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
