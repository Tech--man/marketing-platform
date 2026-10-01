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
 *
 * <p><b>管理写失败留痕</b>（2026-10-01 审计 P2-10）：/api/admin/** 上的非 GET
 * 请求被<b>本进程抛出的</b> BizException 拒绝（40300 越权、41008 乐观锁冲突、
 * 41007 状态机…）时投一条审计——此前控制器只在<b>成功后</b>调 audit()，失败与
 * 被拒的管理写在 admin_audit_log 里完全不可见，而"谁试图改、被什么挡下"恰恰是
 * 审计最该回答的问题。投递走 AuditOutbox 同一条 at-least-once 链路；身份从
 * X-Admin-Token 尽力解析（解析不了记匿名——被拒请求本来就可能没有效凭证）。
 * 审计自身的任何失败只计数，绝不影响错误响应。</p>
 *
 * <p><b>覆盖边界</b>（复审 N-11，别在这里找错日志）：网关侧发出的
 * 40100/40101/40102/40300（凭证缺失/过期/吊销、只读角色）在网关就被拒绝，
 * <b>到不了本 advice、不进审计</b>——那类拒绝的可见面是网关日志与
 * marketing.gateway.auth.* 指标。本类的留痕只回答"过了网关之后被业务规则挡下"
 * 的那一类。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final com.example.marketing.common.audit.AuditOutbox auditOutbox;
    private final com.example.marketing.common.security.AdminRequestIdentity adminIdentity;

    public GlobalExceptionHandler() {
        this(null, null);
    }

    public GlobalExceptionHandler(com.example.marketing.common.audit.AuditOutbox auditOutbox,
                                  com.example.marketing.common.security.AdminRequestIdentity adminIdentity) {
        this.auditOutbox = auditOutbox;
        this.adminIdentity = adminIdentity;
    }

    @ExceptionHandler(BizException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> handleBiz(
            BizException e, jakarta.servlet.http.HttpServletRequest request) {
        log.warn("[biz] code={}, msg={}", e.getCode(), e.getMessage());
        // 鉴权/限流段映射真实 HTTP 状态（2026-09-29 审查收口）：body.code 契约不变
        // （前端只认它），但直连业务端口时基于 HTTP 状态码的监控/熔断/告警终于能看见
        // 401/403/429——原先一律 200，网关层（返回真状态码）与业务层各说各话。
        // P1（2026-09-30 第二轮复审）：原实现用 response.setStatus(...) 写状态——
        // Boot 3.2（Spring 6.1）的 MVC 管道里它生效，Boot 3.4（Spring 6.2）升级后
        // advice 返回 body 的渲染路径会把它盖回 200（ActivityControllerTest 在
        // 3.4.7 基线上实测复现）。改用 ResponseEntity 携带状态，两代管道都稳。
        int code = e.getCode();
        org.springframework.http.HttpStatus status = org.springframework.http.HttpStatus.OK;
        if (code >= 40100 && code < 40200) {
            status = org.springframework.http.HttpStatus.UNAUTHORIZED;
        } else if (code >= 40300 && code < 40400) {
            status = org.springframework.http.HttpStatus.FORBIDDEN;
        } else if (code >= 42900 && code < 43000) {
            status = org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
        }
        auditAdminRejection(e, request);
        return org.springframework.http.ResponseEntity.status(status)
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    /**
     * 管理写被拒的留痕（P2-10）。只看 /api/admin/** 上的非 GET 请求——GET 的 4xxxx
     * 多为查询口径问题，写进审计表会稀释"谁动了什么"的信号。整体 best-effort：
     * 抛任何异常都不许影响错误响应本身（审计不是拒绝链路的一环）。
     */
    private void auditAdminRejection(BizException e, jakarta.servlet.http.HttpServletRequest request) {
        if (auditOutbox == null || request == null) {
            return;
        }
        try {
            String path = request.getRequestURI();
            if (path == null || !path.startsWith("/api/admin")
                    || "GET".equalsIgnoreCase(request.getMethod())) {
                return;
            }
            com.example.marketing.common.security.AdminPrincipal actor = null;
            if (adminIdentity != null) {
                try {
                    actor = adminIdentity.require(request);
                } catch (RuntimeException unauthenticated) {
                    // 被拒请求本就可能没有效凭证：记匿名，而不是让身份解析失败挡住留痕
                }
            }
            auditOutbox.record(new com.example.marketing.common.audit.AuditPayload(
                    actor == null ? null : actor.uid(),
                    actor == null ? "(未认证)" : actor.username(),
                    actor == null ? "-" : actor.role(),
                    "admin.request.rejected",
                    "admin",
                    path,
                    request.getMethod(),
                    path,
                    "写请求被拒（全局异常处理器留痕，非业务成功路径）",
                    e.getCode(),
                    e.getMessage(),
                    com.example.marketing.common.web.ClientIp.of(request),
                    0L,
                    System.currentTimeMillis() / 1000));
        } catch (RuntimeException auditFailure) {
            log.warn("[audit] 管理写拒绝留痕失败（不影响错误响应）: {}", auditFailure.toString());
        }
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

    /**
     * Boot 3.4 / Spring 6.2 的参数校验异常族（W3.2，2026-09-30 第二轮复审）：
     * 升级 3.4.7 后这些类型是活跃路径——@RequestParam/PathVariable 上的约束默认抛
     * HandlerMethodValidationException，query 类型不匹配抛 MethodArgumentTypeMismatch，
     * 服务层 @Validated 抛 ConstraintViolation……没有对应 handler 时全部落进
     * handleUnknown 的 50000"系统繁忙" + 全栈 ERROR 噪音，客户端错误被说成服务器忙。
     */
    @ExceptionHandler({
            org.springframework.web.method.annotation.HandlerMethodValidationException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            jakarta.validation.ConstraintViolationException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleClientParameterErrors(Exception e) {
        log.warn("[biz] 参数校验错误（客户端问题）: {}", e.getMessage());
        return Result.fail(ErrorCode.BAD_REQUEST, "请求参数不合法: " + briefOf(e));
    }

    /** 请求方式不支持：同样是客户端错误，HTTP 状态给 405 而非 500 */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupported(org.springframework.web.HttpRequestMethodNotSupportedException e) {
        log.warn("[biz] 请求方式不支持: {}", e.getMessage());
        return Result.fail(ErrorCode.BAD_REQUEST, "请求方式不支持: " + briefOf(e));
    }

    private static String briefOf(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            return e.getClass().getSimpleName();
        }
        return msg.length() > 120 ? msg.substring(0, 120) : msg;
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
