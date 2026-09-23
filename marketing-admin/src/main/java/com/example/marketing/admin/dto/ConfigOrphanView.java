package com.example.marketing.admin.dto;

/**
 * 库里存在、但当前没有任何代码声明它的键：不生效、不自动删。
 *
 * <p>它是"回滚了带新参数的代码"之后的正常状态，所以只暴露不处置——
 * 自动删等于替运维猜意图。④ 的 ORPHAN 清单负责让它被看见。</p>
 */
public record ConfigOrphanView(String form, String cfgKey, String value,
                               String version, String updatedBy) {
}
