package com.example.marketing.gateway.filter;

import com.example.marketing.gateway.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局鉴权过滤器（演示级静态 Token）。
 *
 * <p>校验 Authorization: Bearer &lt;token&gt;，白名单路径直接放行。
 * 生产替换为 JWT / OAuth2 校验即可，位点与响应契约不变。</p>
 */
@Slf4j
@Component
public class AuthFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GatewayProperties properties;

    public AuthFilter(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        GatewayProperties.Auth auth = properties.getAuth();
        if (!auth.isEnabled() || isWhitelisted(exchange)) {
            return chain.filter(exchange);
        }
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String token = header != null && header.startsWith(BEARER_PREFIX)
                ? header.substring(BEARER_PREFIX.length()) : header;
        if (auth.getToken().equals(token)) {
            return chain.filter(exchange);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 40100);
        body.put("message", "鉴权失败");
        body.put("data", null);
        return writeJson(exchange.getResponse(), HttpStatus.UNAUTHORIZED, body);
    }

    private boolean isWhitelisted(ServerWebExchange exchange) {
        String path = exchange.getRequest().getURI().getPath();
        return properties.getWhitelist().stream().anyMatch(p -> PATH_MATCHER.match(p, path));
    }

    static Mono<Void> writeJson(ServerHttpResponse response, HttpStatus status, Map<String, Object> body) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            log.error("[gateway] 序列化响应失败", e);
            bytes = "{\"code\":50000,\"message\":\"系统繁忙\"}".getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 先于限流执行
        return -100;
    }
}
