package com.example.marketing.discount.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

/**
 * 优惠计算输入（一次购物车/结算页请求）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CalcInput {

    private Long userId;
    /** 限定活动（可空：为空则对全部生效规则计算） */
    private String activityNo;
    /** 用户标签（会员等级、人群包等），与规则 requiredTags 匹配 */
    private Set<String> userTags;
    private List<CalcItem> items;

    /** 购物车原始总价（元） */
    public java.math.BigDecimal totalAmount() {
        return items.stream().map(CalcItem::amount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }
}
