package com.example.marketing.discount.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
    @NotBlank(message = "lineId 必填")
    private String lineId;
    private Long skuId;
    private Long itemId;
    /** 行标签集：品类、品牌、店铺、活动标等，规则条件与之做集合匹配 */
    private Set<String> tags;
    /**
     * 单价（元）。<b>必须有 @NotNull</b>：缺它时 {@link #amount()} 直接 NPE，
     * 一个"字段名拼错"的客户端请求会变成 50000"系统繁忙，请稍后再试"——
     * 客户端错误被说成服务器忙（与 ⑤ 修掉的那个请求体解析失败同一族问题）。
     */
    @NotNull(message = "unitPrice 必填")
    @DecimalMin(value = "0.00", message = "单价不能为负")
    private BigDecimal unitPrice;
    /** 数量。<b>必须非负</b>：负数量会把行小计算成负数，进而让优惠金额反向放大 */
    @Min(value = 1, message = "数量至少为 1")
    private int quantity;

    /** 行原始小计（元），精确到分 */
    public BigDecimal amount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
