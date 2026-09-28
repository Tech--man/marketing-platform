package com.example.marketing.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 网关侧统一的 JSON 拒绝响应。三个 filter（后台鉴权、消费者鉴权、限流）都要在
 * 不进下游的情况下自己写一个响应体，形状必须一致 —— 前端只按 {@code body.code} 分支，
 * 一处形状不同就有一类错误码在前端永远是"未知错误"。
 *
 * <p>从旧的 {@code AuthFilter} 里搬出来：那层演示级静态 token 被真实账号体系取代后，
 * AuthFilter 本身没有职责了，但它的响应契约仍然是这里唯一的形状来源。</p>
 */
@Slf4j
final class GatewayResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GatewayResponses() {
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
}
