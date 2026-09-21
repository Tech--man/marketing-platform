package com.example.marketing.discount.engine;

import com.example.marketing.discount.domain.CalcItem;
import com.example.marketing.discount.domain.CalcResult;
import com.example.marketing.discount.domain.IndexedRule;
import com.example.marketing.discount.domain.PromoRuleDsl;
import com.example.marketing.discount.domain.RuleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分摊器：比例分摊 DOWN + 尾差归末项 + 行级剩余额度约束。
 */
class AllocatorTest {

    /** 构造命中：scope 行 + 范围金额 + 优惠额 */
    private static RuleHit hit(List<CalcItem> scope, BigDecimal scopeAmount, BigDecimal discount) {
        PromoRuleDsl dsl = new PromoRuleDsl();
        dsl.setRuleNo("PR-TEST");
        dsl.setName("测试规则");
        dsl.setType(RuleType.FULL_REDUCTION);
        return new RuleHit(new IndexedRule(dsl, 0), scope, scopeAmount, discount);
    }

    private static CalcItem line(String lineId, String price, int qty, String... tags) {
        return new CalcItem(lineId, 1L, 1L, Set.of(tags), new BigDecimal(price), qty);
    }

    private static BigDecimal sum(List<CalcResult.ItemShare> shares) {
        return shares.stream().map(CalcResult.ItemShare::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal shareOf(List<CalcResult.ItemShare> shares, String lineId) {
        return shares.stream().filter(s -> s.getLineId().equals(lineId))
                .map(CalcResult.ItemShare::getAmount).findFirst().orElse(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("三等除不尽时尾差归范围内末项，分摊总额恰等于优惠额")
    void remainderGoesToLastLine() {
        List<CalcItem> scope = List.of(
                line("A", "10.00", 1), line("B", "20.00", 1), line("C", "30.00", 1));
        Map<String, BigDecimal> used = new HashMap<>();

        List<CalcResult.ItemShare> shares =
                Allocator.apportion(hit(scope, new BigDecimal("60.00"), new BigDecimal("10.00")),
                        new BigDecimal("10.00"), used);

        // 前 n-1 行按 DOWN：1.66 / 3.33，末行吸收尾差 5.01
        assertEquals(new BigDecimal("1.66"), shareOf(shares, "A"));
        assertEquals(new BigDecimal("3.33"), shareOf(shares, "B"));
        assertEquals(new BigDecimal("5.01"), shareOf(shares, "C"));
        assertEquals(new BigDecimal("10.00"), sum(shares));
    }

    @Test
    @DisplayName("末行额度已被占满时尾差顺延给仍有余量的行")
    void remainderFallsBackWhenLastLineFull() {
        List<CalcItem> scope = List.of(
                line("A", "4.00", 1), line("B", "4.00", 1), line("C", "2.00", 1));
        Map<String, BigDecimal> used = new HashMap<>();
        used.put("C", new BigDecimal("2.00")); // C 行已被前一条规则吃满

        List<CalcResult.ItemShare> shares =
                Allocator.apportion(hit(scope, new BigDecimal("10.00"), new BigDecimal("5.00")),
                        new BigDecimal("5.00"), used);

        assertEquals(new BigDecimal("5.00"), sum(shares), "分摊总额不得超优惠额也不许有尾差残留");
        assertEquals(BigDecimal.ZERO, shareOf(shares, "C"), "已被占满的行不应再承担分摊");
        // A/B 先按比例各分 2.00，尾差 1.00 顺延
        assertEquals(new BigDecimal("3.00"), shareOf(shares, "A"));
        assertEquals(new BigDecimal("2.00"), shareOf(shares, "B"));
    }

    @Test
    @DisplayName("多条规则连续分摊时 usedShares 累积，行分摊之和不超过行金额")
    void usedSharesAccumulateAcrossRules() {
        List<CalcItem> scope = List.of(line("A", "5.00", 1), line("B", "5.00", 1));
        Map<String, BigDecimal> used = new HashMap<>();

        RuleHit first = hit(scope, new BigDecimal("10.00"), new BigDecimal("6.00"));
        RuleHit second = hit(scope, new BigDecimal("10.00"), new BigDecimal("6.00"));
        List<CalcResult.ItemShare> s1 = Allocator.apportion(first, new BigDecimal("6.00"), used);
        List<CalcResult.ItemShare> s2 = Allocator.apportion(second, new BigDecimal("6.00"), used);

        assertEquals(new BigDecimal("6.00"), sum(s1));
        // 行总空间仅 10 元：第二轮防御性缩减到剩余 4 元
        assertEquals(new BigDecimal("4.00"), sum(s2));
        assertEquals(0, used.get("A").add(used.get("B")).compareTo(new BigDecimal("10.00")));
    }
}
