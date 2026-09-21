package com.example.marketing.discount.engine;

import com.example.marketing.discount.domain.CalcItem;
import com.example.marketing.discount.domain.IndexedRule;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 规则命中结果：命中规则 + 其适用范围（行集合、范围金额）+ 按范围金额算出的优惠额。
 */
@Getter
@AllArgsConstructor
public class RuleHit {

    private final IndexedRule rule;
    /** 规则适用范围（购物车中满足该规则商品条件的行，保持购物车顺序） */
    private final List<CalcItem> scopeItems;
    /** 范围内原始小计（门槛判断与分摊的基数） */
    private final BigDecimal scopeAmount;
    /** 该规则产生的优惠总额 */
    private final BigDecimal discount;

    /** 规则编号（排序与展示用） */
    public String getRuleNo() {
        return rule.ruleNo();
    }
}
