package com.example.marketing.admin.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * 取真实来源 IP。审计与登录限速都要它，但容器形态下 {@code getRemoteAddr()} 拿到的
 * 永远是网关的 IP —— 不拆 X-Forwarded-For 的话，整张审计表的 ip 列会是同一个值。
 */
final class ClientIp {

    private ClientIp() {
    }

    static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String real = request.getHeader("X-Real-IP");
        return StringUtils.hasText(real) ? real.trim() : request.getRemoteAddr();
    }
}
