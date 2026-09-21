package com.example.marketing.common.exception;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.api.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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
    public Result<Void> handleBiz(BizException e) {
        log.warn("[biz] code={}, msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + defaultMessage(f))
                .findFirst().orElse("参数校验失败");
        return Result.fail(ErrorCode.BAD_REQUEST, detail);
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
