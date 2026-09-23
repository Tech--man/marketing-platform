package com.example.marketing.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * 取真实来源 IP。审计与登录限速都要它，但容器形态下 {@code getRemoteAddr()} 拿到的
 * （③ 起它同时被四个业务服务的审计投递使用，所以放在 common 而不是 admin 的 controller 包里）
 * 永远是网关的 IP —— 不拆 X-Forwarded-For 的话，整张审计表的 ip 列会是同一个值。
 *
 * <p>已知粒度限制（实测）：本仓库拓扑里网关自己就是最外层，它写进 XFF 的是"宿主机在
 * docker 网络里的地址"，所以从宿主机发起的多次登录在限速与审计里都是同一个 IP。
 * 前面真有一层 LB 时需要那层写 XFF 并按跳数取正确一段 —— 那是部署拓扑的事，不在①②里猜。
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String real = request.getHeader("X-Real-IP");
        return StringUtils.hasText(real) ? real.trim() : request.getRemoteAddr();
    }
}
