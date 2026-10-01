package com.example.marketing.common.exception;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.api.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        Result<Void> r = handler.handleBiz(
                BizException.of(ErrorCode.BAD_REQUEST, "值非法"), null).getBody();
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
                    handler.handleBiz(BizException.of(codeOf(c[0]), "x"), null);
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

    // ========== Boot 3.4 / Spring 6.2 参数校验异常族（v3 N-16 → v4 计划 R-10）==========
    // 直接调 handler 方法断言 Result code；@ExceptionHandler 的分发面（"哪类异常进哪个
    // handler"）用反射钉住注解清单——从注解里删掉任一异常类型，此处变红。

    @Test
    @DisplayName("类型不匹配 / 约束违反 / 缺参 → 40000（客户端问题，不是 50000）")
    void clientParameterErrorsAreBadRequest() {
        Result<Void> r1 = handler.handleClientParameterErrors(
                new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                        "abc", Long.class, "activityNo", null, null));
        Result<Void> r2 = handler.handleClientParameterErrors(
                new jakarta.validation.ConstraintViolationException("cv", new java.util.HashSet<>()));
        Result<Void> r3 = handler.handleClientParameterErrors(
                new org.springframework.web.bind.MissingServletRequestParameterException("activityNo", "String"));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), r1.getCode());
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), r2.getCode());
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), r3.getCode());
    }

    @Test
    @DisplayName("HandlerMethodValidationException（@RequestParam 约束）也走 40000")
    void handlerMethodValidationIsBadRequest() throws Exception {
        org.springframework.validation.method.MethodValidationResult result =
                org.mockito.Mockito.mock(org.springframework.validation.method.MethodValidationResult.class);
        Result<Void> r = handler.handleClientParameterErrors(
                new org.springframework.web.method.annotation.HandlerMethodValidationException(result));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), r.getCode());
        assertEquals(false, r.isSuccess());
    }

    @Test
    @DisplayName("请求方式不支持 → 40000 语义 + HTTP 405 状态（@ResponseStatus 钉住）")
    void methodNotSupportedIsClientErrorWith405() throws Exception {
        Result<Void> r = handler.handleMethodNotSupported(
                new org.springframework.web.HttpRequestMethodNotSupportedException("DELETE"));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), r.getCode());
        org.springframework.web.bind.annotation.ResponseStatus status =
                handler.getClass().getMethod("handleMethodNotSupported",
                        org.springframework.web.HttpRequestMethodNotSupportedException.class)
                        .getAnnotation(org.springframework.web.bind.annotation.ResponseStatus.class);
        assertEquals(org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED, status.value());
    }

    @Test
    @DisplayName("N-16 变异闸：@ExceptionHandler 清单必须含全部四类校验异常（删一类即红）")
    void clientParameterHandlerAnnotationCoversAllFour() throws Exception {
        Class<? extends java.lang.annotation.Annotation> annoType =
                org.springframework.web.bind.annotation.ExceptionHandler.class;
        java.lang.annotation.Annotation anno = handler.getClass()
                .getMethod("handleClientParameterErrors", Exception.class)
                .getAnnotation(annoType);
        String valueString = anno.toString();
        for (String required : new String[]{
                "HandlerMethodValidationException",
                "MethodArgumentTypeMismatchException",
                "ConstraintViolationException",
                "MissingServletRequestParameterException"}) {
            assertTrue(valueString.contains(required),
                    "@ExceptionHandler 清单缺 " + required + "——该类异常会落进 handleUnknown 的 50000");
        }
    }
}
