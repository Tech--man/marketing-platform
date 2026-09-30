package com.example.marketing.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * W12 回归：网关注入的 traceId 必须进 MDC（日志 pattern 印 %X{traceId} 的前提）。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    @DisplayName("带 X-Trace-Id 的请求：MDC 在链路执行期间可用、请求后清除")
    void mdcPopulatedDuringChainAndClearedAfter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "abc123def456");
        String[] captured = {null};

        filter.doFilter(request, response, (req, resp) -> captured[0] = MDC.get("traceId"));

        assertEquals("abc123def456", captured[0], "链路执行期间 MDC 必须有 traceId");
        assertNull(MDC.get("traceId"), "请求结束必须清除，防线程池串号");
    }

    @Test
    @DisplayName("无头（直连业务端口）：MDC 为空，日志照常")
    void noHeaderLeavesMdcEmpty() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String[] captured = {null};

        filter.doFilter(request, response, (req, resp) -> captured[0] = MDC.get("traceId"));

        assertNull(captured[0]);
    }

    @Test
    @DisplayName("含控制字符的头值被打码（防日志注入——换行伪造日志行）")
    void controlCharsSanitized() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "ok\nFAKE-LOG-LINE");
        String[] captured = {null};

        filter.doFilter(request, response, (req, resp) -> captured[0] = MDC.get("traceId"));

        assertEquals("okFAKE-LOG-LINE", captured[0],
                "换行必须被剥掉——否则攻击者能让自己的内容伪装成独立日志行");
    }
}
