package com.example.marketing.discount.engine;

import com.example.marketing.discount.config.DiscountProperties;
import com.example.marketing.discount.domain.CalcItem;
import com.example.marketing.discount.domain.CalcInput;
import com.example.marketing.discount.domain.CalcResult;
import com.example.marketing.discount.domain.PromoRuleDsl;
import com.example.marketing.discount.domain.RuleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎基准：1 万条规则快照下，验证位图剪枝后单次计算耗时满足 P99 &lt; 20ms 的量级目标。
 *
 * <p>开发机（非容器、单线程热身后）均值远优于目标值，断言取宽松上限避免 CI 抖动。</p>
 */
class PromoEngineBenchmarkTest {

    private static final int RULE_COUNT = 10_000;
    private static final int WARMUP = 200;
    private static final int ITERATIONS = 1_000;

    private static PromoRuleDsl rule(int i, String tag) {
        PromoRuleDsl dsl = new PromoRuleDsl();
        dsl.setRuleNo("PR" + String.format("%06d", i));
        dsl.setName("规则" + i);
        dsl.setType(RuleType.FULL_REDUCTION);
        dsl.setActivityNo("ACT2026001");
        Set<String> required = new HashSet<>();
        required.add(tag);
        dsl.setRequiredTags(required);
        dsl.setThreshold(new BigDecimal(50 + i % 200));
        dsl.setDiscountValue(new BigDecimal(5 + i % 30));
        dsl.setPriority(i % 100);
        return dsl;
    }

    @Test
    @DisplayName("1 万规则 / 20 行购物车：候选剪枝后单次计算平均耗时应在毫秒级")
    void tenThousandRulesUnderMillisecondScale() {
        List<PromoRuleDsl> dslList = new ArrayList<>(RULE_COUNT);
        for (int i = 0; i < RULE_COUNT; i++) {
            dslList.add(rule(i, "CAT" + (i % 500)));
        }
        long buildStart = System.nanoTime();
        RuleSnapshot snapshot = RuleSnapshot.build(1L, dslList);
        long buildMillis = (System.nanoTime() - buildStart) / 1_000_000;

        // 购物车：20 行，标签覆盖 20 个品类
        Random random = new Random(42);
        List<CalcItem> items = new ArrayList<>();
        for (int j = 0; j < 20; j++) {
            items.add(new CalcItem("L" + j, (long) j, (long) j,
                    Set.of("CAT" + random.nextInt(500)), new BigDecimal("99.90"), 3));
        }
        CalcInput input = new CalcInput(1L, "ACT2026001", Set.of("VIP"), items);

        PromoEngine engine = new PromoEngine(new DiscountProperties(),
                com.example.marketing.common.config.ConfigValues.empty());

        // 候选规模必须远小于规则总量，否则剪枝失效
        int candidateCount = snapshot.candidates(items).size();
        assertTrue(candidateCount < RULE_COUNT / 5,
                "位图剪枝应把 1 万规则裁到 2000 条以内，实际 " + candidateCount);

        for (int i = 0; i < WARMUP; i++) {
            engine.calculate(input, snapshot);
        }
        long start = System.nanoTime();
        CalcResult last = null;
        for (int i = 0; i < ITERATIONS; i++) {
            last = engine.calculate(input, snapshot);
        }
        long avgMicros = (System.nanoTime() - start) / ITERATIONS / 1_000;

        System.out.printf("[bench] 规则=%d, 候选=%d, 快照构建=%dms, 单次计算均值=%dus, 总优惠=%s%n",
                RULE_COUNT, candidateCount, buildMillis, avgMicros, last.getTotalDiscount());

        // 宽松上限：均值 20ms（目标 P99 20ms，均值应远低于此）
        assertTrue(avgMicros < 20_000, "单次计算均值 " + avgMicros + "us 超过 20ms 量级");
    }
}
