package com.example.marketing.common.exception;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.api.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 错误码归一的最后一块：客户端写坏请求体不能被报成"系统繁忙"。
 *
 * <p>这是 ⑤ 的冒烟实测撞出来的：curl 的引号嵌套把 body 弄坏了，返回 50000，
 * 于是"去查服务器"成了一条正确的建议——而真正该改的是那一行脚本。
 * 少一个 handler，就会把每一类客户端错误都变成一次全链路排查。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("业务异常原样带上自己的码与文案")
    void bizExceptionKeepsItsCode() {
        Result<Void> r = handler.handleBiz(BizException.of(ErrorCode.BAD_REQUEST, "值非法")).getBody();
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), r.getCode());
        assertEquals("值非法", r.getMessage());
    }

    @Test
    @DisplayName("W9：鉴权/限流段映射真实 HTTP 状态——直连业务端口时监控终于能看见 401/403/429")
    void authAndRateLimitCodesMapToHttpStatus() {
        int[][] cases = {
                {40100, 401}, {40101, 401}, {40102, 401},
                {40300, 403},
                {42900, 429},
                {40000, 200}, {40400, 200}, {41000, 200}, {50000, 200},
        };
        for (int[] c : cases) {
            org.springframework.http.ResponseEntity<Result<Void>> entity =
                    handler.handleBiz(BizException.of(codeOf(c[0]), "x"));
            assertEquals(c[1], entity.getStatusCode().value(),
                    "code " + c[0] + " 应映射 HTTP " + c[1] + "（body.code 契约不变）");
            assertEquals(c[0], entity.getBody().getCode(), "body.code 契约不变");
        }
    }

    private static ErrorCode codeOf(int code) {
        for (ErrorCode ec : ErrorCode.values()) {
            if (ec.getCode() == code) {
                return ec;
            }
        }
        throw new IllegalArgumentException("no ErrorCode " + code);
    }

    @Test
    @DisplayName("请求体解析失败是 40000，不是 50000")
    void unreadableBodyIsClientError() {
        Result<Void> r = handler.handleUnreadableBody(
                new HttpMessageNotReadableException("bad json", (org.springframework.http.HttpInputMessage) null));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), r.getCode());
        assertEquals(false, r.isSuccess());
    }

    @Test
    @DisplayName("未预期异常仍然是 50000（不能被上面两条吞掉）")
    void unexpectedStaysSystemError() {
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(),
                handler.handleUnknown(new IllegalStateException("boom")).getCode());
    }
}
