package com.example.marketing.common.api;

/**
 * 业务错误码。
 *
 * <p>编码规约：0 成功；4xxxx 客户端/业务可预期错误；5xxxx 系统错误。</p>
 */
public enum ErrorCode {

    SUCCESS(0, "OK"),

    BAD_REQUEST(40000, "请求参数错误"),
    NOT_FOUND(40400, "资源不存在"),
    UNAUTHORIZED(40100, "鉴权失败"),
    /** token 过期：与 40100 分开，客户端凭此决定"静默重登"还是"提示无权限" */
    TOKEN_EXPIRED(40101, "登录已过期，请重新登录"),
    /** 会话被服务端主动作废（登出、强制下线、改密），不该再自动重登 */
    SESSION_REVOKED(40102, "会话已失效，请重新登录"),
    FORBIDDEN(40300, "无权执行该操作"),
    TOO_MANY_REQUESTS(42900, "请求过于频繁，请稍后再试"),

    BIZ_ERROR(41000, "业务处理失败"),
    STATE_INVALID_TRANSITION(41001, "状态机非法流转"),
    STOCK_NOT_ENOUGH(41002, "库存不足"),
    BUDGET_NOT_ENOUGH(41003, "活动预算不足"),
    COUPON_STATUS_INVALID(41004, "券状态不允许该操作"),
    DUPLICATE_REQUEST(41005, "重复请求处理中，请稍后查询结果"),
    RISK_REJECTED(41006, "风控拦截"),
    ACTIVITY_NOT_ONLINE(41007, "活动未上线或已结束"),

    SYSTEM_ERROR(50000, "系统繁忙，请稍后再试"),
    CALC_TIMEOUT(50001, "计算超时，已降级");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
