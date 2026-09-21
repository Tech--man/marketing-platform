package com.example.marketing.common.api;

import lombok.Data;

/**
 * 统一响应结构，所有 HTTP 接口与网关限流响应共用。
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> {

    /** 业务码，0 表示成功，见 {@link ErrorCode} */
    private int code;
    /** 提示信息 */
    private String message;
    /** 业务数据 */
    private T data;

    public static <T> Result<T> ok(T data) {
        return build(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return build(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return build(errorCode.getCode(), message, null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return build(code, message, null);
    }

    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.getCode();
    }

    private static <T> Result<T> build(int code, String message, T data) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        result.setData(data);
        return result;
    }
}
