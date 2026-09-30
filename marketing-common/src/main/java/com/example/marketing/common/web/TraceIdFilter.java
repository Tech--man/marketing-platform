package com.example.marketing.common.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestAttributes;

import java.io.IOException;

/**
 * 把网关注入的 {@code X-Trace-Id} 放进 MDC（W12，2026-09-29 审查收口）。
 *
 * <p>网关是唯一对外入口，它在入口生成 traceId 并透传给所有上游——各业务服务的
 * 日志 pattern 印 {@code %X{traceId}} 后，跨"网关→业务→MQ 消费者"的排障就有了
 * 串起来的钥匙（MQ 消费侧链路属后续工作，先把同步路径接通）。</p>
 *
 * <p>只信网关写的那一份：客户端伪造的 X-Trace-Id 到不了这里——网关 pass() 时
 * 会剥掉再写；直连业务端口的流量没有该头，MDC 为空、日志照常。</p>
 */
public class TraceIdFilter implements Filter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = null;
        if (request instanceof HttpServletRequest http) {
            traceId = http.getHeader(HEADER);
        }
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(MDC_KEY, sanitize(traceId));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** 只留可打印字符，防日志注入（换行伪造日志行） */
    private static String sanitize(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char ch : value.toCharArray()) {
            if (ch >= 0x20 && ch != 0x7F) {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
