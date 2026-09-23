package com.example.marketing.discount.dto;

import com.example.marketing.discount.infrastructure.entity.PromoRuleEntity;

/**
 * 后台规则视图。
 *
 * <p>{@code ruleJson} 是 DSL 全文：这份数据<b>本来就只该给后台看</b>——
 * ③ 之前它挂在 {@code GET /api/discount/rules} 上，等于把整套规则结构、互斥组与门槛
 * 交给共享 demo token 的任何人（母版 §6.1 搬它的正当理由）。</p>
 *
 * <p>{@code version} 必须带出去，编辑时回传做乐观锁。</p>
 */
public record RuleView(
        String ruleNo, String name, String activityNo, String ruleType,
        String mutexGroup, Integer priority, String status, Integer version, String ruleJson) {

    public static RuleView from(PromoRuleEntity e) {
        return new RuleView(e.getRuleNo(), e.getName(), e.getActivityNo(), e.getRuleType(),
                e.getMutexGroup(), e.getPriority(), e.getStatus(), e.getVersion(), e.getRuleJson());
    }
}
