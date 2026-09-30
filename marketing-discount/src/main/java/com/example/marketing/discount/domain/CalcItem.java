package com.example.marketing.discount.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 购物车行项（计算维度：一行 = 一个 sku 的购买项）。
 *
 * <p><b>P1（2026-09-30 第二轮复审）：数值必须限幅</b>——{@code 1e999999999} 这种科学计数法
 * 字面量只有 11 个字符，Jackson 的数字长度限制与 @DecimalMin 的 compareTo 都拦不住它，
 * {@link #amount()} 的 setScale(2) 要为它物化约 415MB 的 BigInteger（单请求 OOM，且降级
 * 路径 totalAmount() 在 catch 里二次引爆）。@Digits 与 DECIMAL(10,2) 列宽对齐，
 * 把量级挡在校验层。</p>
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
    @Size(max = 20, message = "每行最多 20 个标签（位图构建是 O(items×tags×words)）")
    private Set<String> tags;
    /**
     * 单价（元）。<b>必须有 @NotNull</b>：缺它时 {@link #amount()} 直接 NPE，
     * 一个"字段名拼错"的客户端请求会变成 50000"系统繁忙，请稍后再试"——
     * 客户端错误被说成服务器忙（与 ⑤ 修掉的那个请求体解析失败同一族问题）。
     */
    @NotNull(message = "unitPrice 必填")
    @DecimalMin(value = "0.00", message = "单价不能为负")
    @Digits(integer = 10, fraction = 2, message = "单价最多 10 位整数、2 位小数")
    private BigDecimal unitPrice;
    /** 数量。<b>必须非负</b>：负数量会把行小计算成负数，进而让优惠金额反向放大 */
    @Min(value = 1, message = "数量至少为 1")
    @Max(value = 10000, message = "数量最多 10000")
    private int quantity;

    /** 行原始小计（元），精确到分 */
    public BigDecimal amount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
