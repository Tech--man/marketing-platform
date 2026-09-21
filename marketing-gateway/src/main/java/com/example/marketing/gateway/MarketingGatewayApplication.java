package com.example.marketing.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API 网关：统一入口，负责路由转发、Token 鉴权、Redis+Lua 滑动窗口限流。
 *
 * <p>分层限流的第一层（网关级），下游各服务仍保留自身保护（Sentinel/信号量）。</p>
 */
@SpringBootApplication
public class MarketingGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingGatewayApplication.class, args);
    }
}
