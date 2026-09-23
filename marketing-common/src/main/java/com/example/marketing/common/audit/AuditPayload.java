package com.example.marketing.common.audit;

/**
 * 一条审计的跨进程载荷，字段与 {@code admin_audit_log} 的列一一对应。
 *
 * <p>为什么业务侧要自己造载荷（段内 spec §4.1）：before/after 只有真正做完那次写的进程知道，
 * 而表的所有权在 marketing-admin —— 每服务一库档业务进程连不上那张表。所以业务侧投
 * {@code mkt:audit:pending}，admin 定时 drain 落表：表不下放，事实也不转移。</p>
 *
 * <p>{@code requestSummary} 必须由调用方在写入前就脱敏（admin 侧的 {@code RequestSummary} 同一口径），
 * 禁止传原始 body。before/after 拼在这段里（{@code from=…, to=…, version=…}），
 * 沿用 admin 既有的记法，免得同一张表出现两种"改了什么"的存法。</p>
 *
 * @param epochSecond 业务侧完成动作的时刻（不是 drain 落表的时刻——审计时间线必须是动作时间）
 */
public record AuditPayload(
        Long actorId,
        String actorName,
        String role,
        String action,
        String resourceType,
        String resourceId,
        String method,
        String path,
        String requestSummary,
        int resultCode,
        String errorMsg,
        String ip,
        long costMs,
        long epochSecond) {
}
