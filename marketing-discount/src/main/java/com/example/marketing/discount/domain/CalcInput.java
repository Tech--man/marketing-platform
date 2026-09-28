package com.example.marketing.discount.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
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

    /** 由控制器从验过签名的身份里填入；{@code @JsonIgnore} 让请求体根本改不动它 */
    @JsonIgnore
    private Long userId;
    /** 限定活动（可空：为空则对全部生效规则计算） */
    private String activityNo;
    /** 用户标签（会员等级、人群包等），与规则 requiredTags 匹配 */
    private Set<String> userTags;
    /** 行项：@Valid 是逐行校验的开关，缺它则 CalcItem 上的约束全都形同不存在 */
    @NotEmpty(message = "购物车不能为空")
    @Valid
    private List<CalcItem> items;

    /** 购物车原始总价（元） */
    public java.math.BigDecimal totalAmount() {
        return items.stream().map(CalcItem::amount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }
}
