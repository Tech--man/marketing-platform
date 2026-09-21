package com.example.marketing.discount.engine;

import com.example.marketing.discount.domain.PromoRuleDsl;
import com.example.marketing.discount.domain.RuleType;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 单规则优惠金额计算（基于规则适用范围的小计金额）。
 *
 * <p>精度约定：金额单位元，结果保留 2 位；折扣按 HALF_UP 取分。</p>
 */
public final class DiscountCalculator {

    private static final BigDecimal TEN = BigDecimal.TEN;

    private DiscountCalculator() {
    }

    /**
     * 计算规则在给定范围金额下的优惠额；门槛不满足返回 0（视为未命中）。
     */
    public static BigDecimal calc(PromoRuleDsl dsl, BigDecimal scopeAmount) {
        if (scopeAmount == null || scopeAmount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal threshold = dsl.getThreshold() == null ? BigDecimal.ZERO : dsl.getThreshold();
        if (scopeAmount.compareTo(threshold) < 0) {
            return BigDecimal.ZERO;
        }
        RuleType type = dsl.getType();
        BigDecimal discount = switch (type) {
            case FULL_REDUCTION -> nullToZero(dsl.getDiscountValue());
            case DISCOUNT -> {
                if (dsl.getDiscountRate() == null) {
                    yield BigDecimal.ZERO;
                }
                // 优惠 = 范围金额 * (1 - 折率/10)，如 8.5 折 => 优惠 15%
                BigDecimal rate = dsl.getDiscountRate().divide(TEN, 4, RoundingMode.HALF_UP);
                yield scopeAmount.multiply(BigDecimal.ONE.subtract(rate)).setScale(2, RoundingMode.HALF_UP);
            }
            case LADDER -> bestLadder(dsl, scopeAmount);
        };
        // 优惠不得超过范围金额
        return discount.min(scopeAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    /** 阶梯满减：取满足门槛的最高档 */
    private static BigDecimal bestLadder(PromoRuleDsl dsl, BigDecimal scopeAmount) {
        if (dsl.getLadderSteps() == null || dsl.getLadderSteps().isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal best = BigDecimal.ZERO;
        for (PromoRuleDsl.LadderStep step : dsl.getLadderSteps()) {
            if (step.getThreshold() != null && scopeAmount.compareTo(step.getThreshold()) >= 0) {
                best = nullToZero(step.getDiscountValue());
            }
        }
        return best;
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
