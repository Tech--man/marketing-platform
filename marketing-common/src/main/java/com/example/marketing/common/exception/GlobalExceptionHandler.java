package com.example.marketing.common.exception;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.api.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器：业务异常转统一响应，系统异常兜底并告警日志。
 *
 * <p>由 MarketingCommonAutoConfiguration 在 Servlet 环境下自动注册。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e, jakarta.servlet.http.HttpServletResponse response) {
        log.warn("[biz] code={}, msg={}", e.getCode(), e.getMessage());
        // 鉴权/限流段映射真实 HTTP 状态（2026-09-29 审查收口）：body.code 契约不变
        // （前端只认它），但直连业务端口时基于 HTTP 状态码的监控/熔断/告警终于能看见
        // 401/403/429——原先一律 200，网关层（返回真状态码）与业务层各说各话。
        int code = e.getCode();
        if (code >= 40100 && code < 40200) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
        } else if (code >= 40300 && code < 40400) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
        } else if (code >= 42900 && code < 43000) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        }
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + defaultMessage(f))
                .findFirst().orElse("参数校验失败");
        return Result.fail(ErrorCode.BAD_REQUEST, detail);
    }

    /**
     * 请求体解析不了是客户端错误，不是系统错误。
     *
     * <p>没有这个 handler 时它落进 {@link #handleUnknown} 的 50000"系统繁忙"：调用方拿到
     * "稍后再试"就去重试，而重试一万次也不会成功——少了一个引号被说成服务器忙，
     * 是最容易把人引向错误排查方向的一类误报（⑤ 的冒烟实测撞到过）。</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("[biz] 请求体解析失败: {}", e.getMessage());
        return Result.fail(ErrorCode.BAD_REQUEST, "请求体不是可解析的 JSON，检查引号与字段名");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnknown(Exception e) {
        log.error("[system] 未预期异常", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }

    /** 根路径与 favicon 等探测请求不是系统异常，按 404 静默返回，避免污染错误日志 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        log.debug("[web] 资源不存在: {}", e.getResourcePath());
        return Result.fail(ErrorCode.NOT_FOUND);
    }

    private String defaultMessage(FieldError error) {
        return error.getDefaultMessage() == null ? "非法" : error.getDefaultMessage();
    }
}
