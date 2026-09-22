package com.example.marketing.admin.audit;

/**
 * 一条审计。
 *
 * @param actorId       操作者账号 ID，登录失败时可为 null（可能根本没有这个账号）
 * @param requestSummary 必须已过 {@link RequestSummary}，禁止传入原始 body
 */
public record AuditRecord(
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
        long costMs) {

    public static AuditRecord ofAction(Long actorId, String actorName, String role, String action,
                                       String resourceType, String resourceId, String ip) {
        return new AuditRecord(actorId, actorName, role, action, resourceType, resourceId,
                "", "", "", 0, "", ip, 0);
    }
}
