package com.example.marketing.discount.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 优惠计算结果（含命中规则明细与行级分摊）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CalcResult {

    /** 购物车原始总价（元） */
    private BigDecimal originalAmount;
    /** 总优惠（元） */
    private BigDecimal totalDiscount;
    /** 应付（元）= original - totalDiscount */
    private BigDecimal payableAmount;
    /** 命中并应用的规则明细（按应用顺序） */
    private List<AppliedRule> appliedRules;
    /** 是否降级结果（超时/异常时返回原价） */
    private boolean degraded;

    /** 一条已应用规则及其行级分摊 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AppliedRule {
        private String ruleNo;
        private String name;
        private RuleType type;
        /** 该规则优惠总额（元） */
        private BigDecimal discountAmount;
        /** 行级分摊（金额比例 + 尾差归该行范围内末项） */
        private List<ItemShare> shares;
    }

    /** 单行分摊 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemShare {
        private String lineId;
        /** 该行分摊到的优惠（元） */
        private BigDecimal amount;
    }

    /** 降级兜底：原价结果 */
    public static CalcResult original(BigDecimal total) {
        CalcResult result = new CalcResult();
        result.setOriginalAmount(total);
        result.setTotalDiscount(BigDecimal.ZERO);
        result.setPayableAmount(total);
        result.setAppliedRules(List.of());
        result.setDegraded(true);
        return result;
    }
}
