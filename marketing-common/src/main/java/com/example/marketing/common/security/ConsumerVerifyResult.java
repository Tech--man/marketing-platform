package com.example.marketing.common.security;

/**
 * 消费者 token 校验结果。与 {@link TokenVerifyResult} 分开而不是泛型化：
 * 后者的 {@code claims()} 返回 {@link AdminClaims}，两者字段集不同（rol/ver vs type），
 * 强行合并会让调用点拿到一个"可能是后台也可能是消费者"的联合类型 —— 那正是
 * "两套凭证不互通"这条断言最该由类型系统兜住的地方。
 */
public record ConsumerVerifyResult(ConsumerVerifyResult.Status status, ConsumerClaims claims) {

    public enum Status {
        OK, EXPIRED, BAD_SIGNATURE, MALFORMED,
        /** 签名与有效期都对，但 type 不是所求的那一种（典型：拿 refresh 当 access 用） */
        WRONG_TYPE
    }

    public static ConsumerVerifyResult ok(ConsumerClaims claims) {
        return new ConsumerVerifyResult(Status.OK, claims);
    }

    public static ConsumerVerifyResult fail(Status status) {
        return new ConsumerVerifyResult(status, null);
    }
}
