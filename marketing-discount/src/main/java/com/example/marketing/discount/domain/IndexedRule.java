package com.example.marketing.discount.domain;

import lombok.Getter;

import java.util.Set;

/**
 * 索引中的规则：DSL + 位图序号。
 *
 * <p>规则快照构建时为每条规则分配自增 ordinal，倒排索引 long[] 位图以 ordinal
 * 为位下标标记"某标签被哪些规则引用"，候选剪枝即按购物车标签做位图 OR。</p>
 */
@Getter
public class IndexedRule {

    private final PromoRuleDsl dsl;
    /** 位图下标（快照内自增，仅在单个快照内有效） */
    private final int ordinal;
    /** 预构建查找集，避免命中校验时反复解析 */
    private final Set<String> requiredTags;
    private final Set<String> excludeTags;

    public IndexedRule(PromoRuleDsl dsl, int ordinal) {
        this.dsl = dsl;
        this.ordinal = ordinal;
        this.requiredTags = dsl.getRequiredTags() == null ? Set.of() : dsl.getRequiredTags();
        this.excludeTags = dsl.getExcludeTags() == null ? Set.of() : dsl.getExcludeTags();
    }

    public String ruleNo() {
        return dsl.getRuleNo();
    }
}
