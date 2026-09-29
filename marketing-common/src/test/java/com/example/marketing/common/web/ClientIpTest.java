package com.example.marketing.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * H3 回归（2026-09-29 架构审查）：X-Real-IP 从采信链里移除。
 *
 * <p>网关已把 XFF 配成覆写式转发（{@code x-forwarded.for-append: false}），经网关的
 * 请求 XFF 恒为网关写入的单值；X-Real-IP 没有任何合法写入方，它能出现只可能是
 * 直连者自报的——采信它等于审计/限速的 IP 维度交回客户端填。</p>
 */
class ClientIpTest {

    @Test
    @DisplayName("经网关：XFF 首段（网关写入的单值）照旧采信，多段只取第一段")
    void gatewayWrittenXffFirstSegment() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        assertEquals("203.0.113.7", ClientIp.of(request));
    }

    @Test
    @DisplayName("H3：X-Real-IP 不再被采信——它只能是直连者自报的，落到真实对端地址")
    void xRealIpIsIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", "1.2.3.4");
        request.setRemoteAddr("192.0.2.9");
        assertEquals("192.0.2.9", ClientIp.of(request));
    }

    @Test
    @DisplayName("两头都没有：直接用 remoteAddr（直连业务端口的正常形状）")
    void fallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.3");
        assertEquals("198.51.100.3", ClientIp.of(request));
    }
}
