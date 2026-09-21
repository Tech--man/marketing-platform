package com.example.marketing.discount.engine;

import com.example.marketing.discount.config.DiscountProperties;
import com.example.marketing.discount.domain.CalcItem;
import com.example.marketing.discount.domain.CalcInput;
import com.example.marketing.discount.domain.CalcResult;
import com.example.marketing.discount.domain.IndexedRule;
import com.example.marketing.discount.domain.PromoRuleDsl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 优惠计算引擎（纯内存计算，无 IO，可被压测基准直接调用）。
 *
 * <p>流水线：位图候选剪枝 → 逐条范围匹配与门槛校验 → 最优组合选择 →
 * 按应用顺序分摊（行级 cap + 尾差归末项）。</p>
 */
@Component
@RequiredArgsConstructor
public class PromoEngine {

    private final DiscountProperties properties;

    public CalcResult calculate(CalcInput input, RuleSnapshot snapshot) {
        BigDecimal original = input.totalAmount();
        Set<String> userTags = input.getUserTags() == null ? Set.of() : input.getUserTags();

        // 1. 候选剪枝 + 2. 精确匹配
        List<RuleHit> hits = new ArrayList<>();
        for (IndexedRule rule : snapshot.candidates(input.getItems())) {
            RuleHit hit = matchOne(rule, input, userTags);
            if (hit != null) {
                hits.add(hit);
            }
        }

        // 3. 最优组合
        List<RuleHit> chosen = CombinationSelector.select(hits, properties.getMaxRulesPerOrder());

        // 4. 按应用顺序分摊（usedShares 累积行级已占额度）
        Map<String, BigDecimal> usedShares = new HashMap<>();
        List<CalcResult.AppliedRule> applied = new ArrayList<>(chosen.size());
        BigDecimal totalDiscount = BigDecimal.ZERO;
        for (RuleHit hit : chosen) {
            List<CalcResult.ItemShare> shares = Allocator.apportion(hit, hit.getDiscount(), usedShares);
            BigDecimal actual = shares.stream().map(CalcResult.ItemShare::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (actual.signum() <= 0) {
                continue; // 行级额度已被占满，该规则实际不产生优惠
            }
            PromoRuleDsl dsl = hit.getRule().getDsl();
            applied.add(new CalcResult.AppliedRule(dsl.getRuleNo(), dsl.getName(), dsl.getType(), actual, shares));
            totalDiscount = totalDiscount.add(actual);
        }
        return new CalcResult(original, totalDiscount, original.subtract(totalDiscount), applied, false);
    }

    /** 单条规则精确校验：用户标签 → 商品范围 → 门槛 → 优惠额 */
    private RuleHit matchOne(IndexedRule rule, CalcInput input, Set<String> userTags) {
        PromoRuleDsl dsl = rule.getDsl();
        // 活动过滤
        if (input.getActivityNo() != null && dsl.getActivityNo() != null
                && !input.getActivityNo().equals(dsl.getActivityNo())) {
            return null;
        }
        // 用户维度条件：user: 前缀标签须全部命中
        Set<String> requiredItemTags = new HashSet<>();
        for (String tag : rule.getRequiredTags()) {
            if (tag.startsWith(RuleSnapshot.USER_TAG_PREFIX)) {
                if (!userTags.contains(tag.substring(RuleSnapshot.USER_TAG_PREFIX.length()))) {
                    return null;
                }
            } else {
                requiredItemTags.add(tag);
            }
        }
        // 商品范围：行标签需包含全部商品维度 requiredTags，且不触碰 excludeTags
        List<CalcItem> scope = new ArrayList<>();
        BigDecimal scopeAmount = BigDecimal.ZERO;
        for (CalcItem item : input.getItems()) {
            Set<String> itemTags = item.getTags() == null ? Set.of() : item.getTags();
            if (!java.util.Collections.disjoint(itemTags, rule.getExcludeTags())) {
                continue;
            }
            if (itemTags.containsAll(requiredItemTags)) {
                scope.add(item);
                scopeAmount = scopeAmount.add(item.amount());
            }
        }
        if (scope.isEmpty()) {
            return null;
        }
        BigDecimal discount = DiscountCalculator.calc(dsl, scopeAmount);
        if (discount.signum() <= 0) {
            return null;
        }
        return new RuleHit(rule, scope, scopeAmount, discount);
    }
}
