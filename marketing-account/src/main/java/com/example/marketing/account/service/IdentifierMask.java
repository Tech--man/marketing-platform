package com.example.marketing.account.service;

/**
 * identifier（将来可承载手机号/邮箱）的日志脱敏（2026-09-29 审查第四批）。
 *
 * <p>库表里的 identifier 是业务键必须原文；但日志会被收集/归档，明文 PII 进日志
 * 等于把"存得下"变成"删不掉"。规则：保留前 2 后 2，中间按长度打码；
 * ≤5 位整段打码（保留过多等于没脱）。</p>
 */
public final class IdentifierMask {

    private IdentifierMask() {
    }

    public static String mask(String identifier) {
        if (identifier == null || identifier.length() <= 5) {
            return "****";
        }
        int keep = 2;
        return identifier.substring(0, keep) + "*".repeat(identifier.length() - keep * 2)
                + identifier.substring(identifier.length() - keep);
    }
}
