package com.example.marketing.discount.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最优优惠组合选择。
 *
 * <p>约束模型：互斥组内至多命中一条；不同组（含无组规则）可叠加；整单叠加规则数
 * 不超过 maxRulesPerOrder。组内独立最优不等于全局最优（叠加数量受限时），
 * 组合空间 ≤ {@value #ENUMERATE_LIMIT} 时精确枚举，超限退化为贪心：
 * 每组先取组内最优（priority 降序、优惠额降序），再按优惠额降序取前 maxRules 条。</p>
 */
public final class CombinationSelector {

    /** 精确枚举的组合空间上限（∏(组内命中数+1)） */
    static final int ENUMERATE_LIMIT = 20_000;

    private CombinationSelector() {
    }

    /**
     * @param hits             全部命中规则（含范围与优惠额）
     * @param maxRulesPerOrder 整单最多叠加规则数
     * @return 选中的命中规则（按 priority 降序的应用顺序）
     */
    public static List<RuleHit> select(List<RuleHit> hits, int maxRulesPerOrder) {
        if (hits.isEmpty()) {
            return List.of();
        }
        // 分组：mutexGroup 为空的规则各自成组（天然可叠加）
        Map<String, List<RuleHit>> groups = new LinkedHashMap<>();
        for (RuleHit hit : hits) {
            String group = hit.getRule().getDsl().getMutexGroup();
            String key = (group == null || group.isBlank())
                    ? "SOLO:" + hit.getRule().ruleNo() : group;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(hit);
        }
        // 组内按 (priority desc, discount desc) 排序，首选在头
        Comparator<RuleHit> bestFirst = Comparator
                .comparingInt((RuleHit h) -> h.getRule().getDsl().getPriority()).reversed()
                .thenComparing(Comparator.comparing(RuleHit::getDiscount).reversed());
        List<List<RuleHit>> groupList = new ArrayList<>();
        for (List<RuleHit> group : groups.values()) {
            group.sort(bestFirst);
            groupList.add(group);
        }
        // 组间按"组内最优优惠额"降序（贪心次序）
        groupList.sort(Comparator.comparing((List<RuleHit> g) -> g.get(0).getDiscount()).reversed());

        if (groupList.size() <= maxRulesPerOrder) {
            // 每组至少可选 1 条且数量不超限：直接取各组最优即全局最优
            List<RuleHit> chosen = new ArrayList<>(groupList.size());
            for (List<RuleHit> group : groupList) {
                chosen.add(group.get(0));
            }
            return applyOrder(chosen);
        }
        // 组合空间：每组"选哪条或放弃"的乘积（放弃=选空，但枚举中表现为不贡献优惠）
        long space = 1;
        for (List<RuleHit> group : groupList) {
            space *= (group.size() + 1L);
            if (space > ENUMERATE_LIMIT) {
                break;
            }
        }
        List<RuleHit> result = space <= ENUMERATE_LIMIT
                ? enumerate(groupList, maxRulesPerOrder)
                : greedy(groupList, maxRulesPerOrder);
        return applyOrder(result);
    }

    /** 精确枚举：自每组各选一条（或不选），总数 ≤ maxRules，目标总优惠最大 */
    private static List<RuleHit> enumerate(List<List<RuleHit>> groupList, int maxRules) {
        int g = groupList.size();
        int[] bestChoice = new int[g];
        BigDecimal best = BigDecimal.valueOf(-1);
        // 混合基数进制：第 i 组取值 0..size（0=放弃，k=选第 k-1 条）
        int[] mixed = new int[g];
        for (int i = 0; i < g; i++) {
            mixed[i] = groupList.get(i).size() + 1;
        }
        int[] state = new int[g]; // 当前枚举位
        outer:
        while (true) {
            // 评估当前组合
            int count = 0;
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 0; i < g; i++) {
                if (state[i] > 0) {
                    count++;
                    total = total.add(groupList.get(i).get(state[i] - 1).getDiscount());
                }
            }
            if (count <= maxRules && total.compareTo(best) > 0) {
                best = total;
                for (int i = 0; i < g; i++) {
                    bestChoice[i] = state[i] - 1;
                }
            }
            // 进位
            for (int i = 0; i < g; i++) {
                state[i]++;
                if (state[i] < mixed[i]) {
                    continue outer;
                }
                state[i] = 0;
            }
            break;
        }
        List<RuleHit> chosen = new ArrayList<>();
        for (int i = 0; i < g; i++) {
            if (bestChoice[i] >= 0) {
                chosen.add(groupList.get(i).get(bestChoice[i]));
            }
        }
        return chosen;
    }

    /** 贪心退化：各组最优按优惠额降序取前 maxRules */
    private static List<RuleHit> greedy(List<List<RuleHit>> groupList, int maxRules) {
        List<RuleHit> chosen = new ArrayList<>(maxRules);
        for (int i = 0; i < Math.min(maxRules, groupList.size()); i++) {
            chosen.add(groupList.get(i).get(0));
        }
        return chosen;
    }

    /** 应用顺序：priority 降序（高优规则先应用、先占行级额度） */
    private static List<RuleHit> applyOrder(List<RuleHit> chosen) {
        chosen.sort(Comparator
                .comparingInt((RuleHit h) -> h.getRule().getDsl().getPriority()).reversed()
                .thenComparing(RuleHit::getRuleNo));
        return chosen;
    }
}
