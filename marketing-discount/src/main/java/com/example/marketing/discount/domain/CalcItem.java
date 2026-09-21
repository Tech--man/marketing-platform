package com.example.marketing.discount.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 购物车行项（计算维度：一行 = 一个 sku 的购买项）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CalcItem {

    /** 行号（购物车内唯一，分摊结果按此回填） */
    private String lineId;
    private Long skuId;
    private Long itemId;
    /** 行标签集：品类、品牌、店铺、活动标等，规则条件与之做集合匹配 */
    private Set<String> tags;
    /** 单价（元） */
    private BigDecimal unitPrice;
    private int quantity;

    /** 行原始小计（元），精确到分 */
    public BigDecimal amount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
