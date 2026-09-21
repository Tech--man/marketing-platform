package com.example.marketing.discount.engine;

import com.example.marketing.discount.domain.CalcItem;
import com.example.marketing.discount.domain.IndexedRule;
import com.example.marketing.discount.domain.PromoRuleDsl;
import com.example.marketing.discount.domain.RuleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 互斥组组合选择：组内竞争、数量约束下的最优组合与应用顺序。
 */
class CombinationSelectorTest {

    private static final List<CalcItem> ANY_SCOPE =
            List.of(new CalcItem("L1", 1L, 1L, Set.of(), new BigDecimal("1000.00"), 1));

    private static RuleHit hit(String ruleNo, String mutexGroup, int priority, String discount) {
        PromoRuleDsl dsl = new PromoRuleDsl();
        dsl.setRuleNo(ruleNo);
        dsl.setName(ruleNo);
        dsl.setType(RuleType.FULL_REDUCTION);
        dsl.setMutexGroup(mutexGroup);
        dsl.setPriority(priority);
        return new RuleHit(new IndexedRule(dsl, 0), ANY_SCOPE,
                new BigDecimal("1000.00"), new BigDecimal(discount));
    }

    private static List<String> ruleNos(List<RuleHit> hits) {
        return hits.stream().map(RuleHit::getRuleNo).collect(Collectors.toList());
    }

    @Test
    @DisplayName("同互斥组只保留一条：优先级高者优先，同优先级取优惠更大")
    void mutexGroupKeepsSingleBest() {
        List<RuleHit> hits = List.of(
                hit("HIGH", "G", 100, "10.00"),
                hit("LOW", "G", 10, "99.00"),
                hit("TIE_A", "T", 50, "5.00"),
                hit("TIE_B", "T", 50, "8.00"));

        List<RuleHit> chosen = CombinationSelector.select(hits, 5);

        assertEquals(2, chosen.size());
        assertTrue(ruleNos(chosen).containsAll(List.of("HIGH", "TIE_B")));
    }

    @Test
    @DisplayName("组数超过叠加上限时走精确枚举，选总优惠最大的组合（允许组内选次优）")
    void enumerateFindsGlobalOptimum() {
        // 3 个组、上限 2 条：G3 的头名（priority 高）优惠很小，枚举应改选 G3 次优
        List<RuleHit> hits = List.of(
                hit("X", "G1", 10, "5.00"),
                hit("Y", "G2", 5, "4.00"),
                hit("Z1", "G3", 10, "1.00"),
                hit("Z2", "G3", 1, "50.00"));

        List<RuleHit> chosen = CombinationSelector.select(hits, 2);

        assertEquals(List.of("X", "Z2"), ruleNos(chosen), "最优组合 5+50，应用顺序按 priority 降序");
    }

    @Test
    @DisplayName("组数不超上限时各组直接取最优，全部叠加")
    void withinLimitTakesAllGroupBest() {
        List<RuleHit> hits = List.of(
                hit("A1", "GA", 1, "3.00"),
                hit("A2", "GA", 9, "2.00"),
                hit("B1", null, 5, "4.00"));

        List<RuleHit> chosen = CombinationSelector.select(hits, 5);

        assertEquals(2, chosen.size());
        // A2 优先级高胜出；B1 独立组直接入选；应用顺序 priority desc：B1(5) → A2(9)? 按降序 A2 在前
        assertEquals(List.of("A2", "B1"), ruleNos(chosen));
    }

    @Test
    @DisplayName("空命中返回空组合")
    void emptyHits() {
        assertTrue(CombinationSelector.select(List.of(), 5).isEmpty());
    }
}
