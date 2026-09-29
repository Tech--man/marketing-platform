package com.example.marketing.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * 取真实来源 IP。审计与登录限速都要它，但容器形态下 {@code getRemoteAddr()} 拿到的
 * （③ 起它同时被四个业务服务的审计投递使用，所以放在 common 而不是 admin 的 controller 包里）
 * 永远是网关的 IP —— 不拆 X-Forwarded-For 的话，整张审计表的 ip 列会是同一个值。
 *
 * <p><b>为什么只看 XFF、不再回退 X-Real-IP</b>（H3，2026-09-29 架构审查收口）：网关已把
 * XFF 配成覆写式转发（{@code x-forwarded.for-append: false}），经网关到达的请求里 XFF
 * 恒为网关写入的单值、可信；X-Real-IP 则谁都不写——它能出现只可能是直连者自报的，
 * 采信它等于把审计 ip 列交回给客户端填。XFF 缺失（直连业务端口且不带 XFF）时回退
 * {@code getRemoteAddr()}，拿到的就是真实对端。</p>
 *
 * <p><b>已知残余</b>：直连业务端口且自带 XFF 的请求（绕过网关），这里取到的仍是自报值。
 * 这一片靠部署收口兜底（LITE 的 8085 只绑回环、业务端口不发布），HTTP 层没有不共享
 * 秘密就能区分"网关写的 XFF"与"客户端写的 XFF"的办法。</p>
 *
 * <p>已知粒度限制（实测）：本仓库拓扑里网关自己就是最外层，它写进 XFF 的是"宿主机在
 * docker 网络里的地址"，所以从宿主机发起的多次登录在限速与审计里都是同一个 IP。
 * 前面真有一层 LB 时需要那层写 XFF 并按跳数取正确一段 —— 那是部署拓扑的事，不在①②里猜。</p>
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
        return request.getRemoteAddr();
    }
}
