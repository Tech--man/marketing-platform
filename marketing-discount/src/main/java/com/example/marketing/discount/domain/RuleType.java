package com.example.marketing.discount.domain;

/**
 * 促销规则类型（内置三类，DSL 的 type 字段）。
 */
public enum RuleType {

    /** 满减：范围内 subtotal 达到 threshold 减 reduction */
    FULL_REDUCTION,

    /** 折扣：范围内金额按 discountRate（如 8.5 = 85 折）打折 */
    DISCOUNT,

    /** 阶梯满减：按 ladder 中满足的最高阶梯减免 */
    LADDER
}
