package com.example.marketing.common.idempotent;

/**
 * 幂等记录状态机：PROCESSING → SUCCESS / FAILED（FAILED 允许被重新抢占执行）。
 */
public enum IdempotentStatus {

    PROCESSING,
    SUCCESS,
    FAILED
}
