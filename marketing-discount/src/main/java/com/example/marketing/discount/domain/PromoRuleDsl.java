package com.example.marketing.discount.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 促销规则 DSL（JSON 反序列化目标）。
 *
 * <p>一条规则 = 条件（标签集合）+ 动作（满减/折扣/阶梯）+ 互斥组 + 叠加策略 + 优先级。
 * 存储在 promo_rule.rule_json 字段，解析后进入倒排索引。</p>
 */
@Data
public class PromoRuleDsl {

    /** 规则编号（业务唯一） */
    private String ruleNo;
    /** 规则名称 */
    private String name;
    /** 规则类型 */
    private RuleType type;
    /** 生效活动编号（绑定活动生命周期，活动下线则规则失效） */
    private String activityNo;
    /** 命中条件：商品需同时满足的全部标签（AND）。空表示全品类 */
    private Set<String> requiredTags;
    /** 排除标签：命中任一即不适用（NOT） */
    private Set<String> excludeTags;
    /** 金额门槛（元）：凑单范围内小计 >= threshold 才触发 */
    private BigDecimal threshold;
    /** 满减/阶梯：减免金额（元）；type=LADDER 时忽略，取 ladderSteps 中最高满足档 */
    private BigDecimal discountValue;
    /** 折扣：折率，如 8.5 表示 85 折（type=DISCOUNT 时必填） */
    private BigDecimal discountRate;
    /** 阶梯满减档位（type=LADDER）：按 threshold 升序，取满足的最高档 */
    private List<LadderStep> ladderSteps;
    /** 互斥组名：同组规则最多命中一条；null/空表示可叠加组 */
    private String mutexGroup;
    /** 优先级：同互斥组内竞争时，值大者优先（同值则取优惠额更高者） */
    private int priority;
    /** 单用户每场次最多享受该规则次数 */
    private int perUserLimit;

    /**
     * 阶梯满减档位。
     */
    @Data
    public static class LadderStep {
        /** 该档门槛金额（元） */
        private BigDecimal threshold;
        /** 该档减免金额（元） */
        private BigDecimal discountValue;
    }
}
