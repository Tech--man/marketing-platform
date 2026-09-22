package com.example.marketing.common.security;

/**
 * token 校验结果。失败原因要分得开：过期可以提示重登，验签失败可能是伪造，
 * 格式错误则是垃圾请求 —— 三者在审计与限流上的处理不一样。
 */
public record TokenVerifyResult(TokenVerifyResult.Status status, AdminClaims claims) {

    public enum Status {
        OK, EXPIRED, BAD_SIGNATURE, MALFORMED
    }

    public static TokenVerifyResult ok(AdminClaims claims) {
        return new TokenVerifyResult(Status.OK, claims);
    }

    public static TokenVerifyResult fail(Status status) {
        return new TokenVerifyResult(status, null);
    }
}
