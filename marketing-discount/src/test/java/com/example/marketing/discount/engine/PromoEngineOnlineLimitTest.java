package com.example.marketing.discount.engine;

import com.example.marketing.common.config.ConfigSchemaRegistry;
import com.example.marketing.common.config.ConfigSnapshot;
import com.example.marketing.common.config.ConfigType;
import com.example.marketing.common.config.ConfigValues;
import com.example.marketing.discount.config.DiscountConfigDefinitions;
import com.example.marketing.discount.config.DiscountProperties;
import com.example.marketing.discount.domain.CalcItem;
import com.example.marketing.discount.domain.CalcInput;
import com.example.marketing.discount.domain.CalcResult;
import com.example.marketing.discount.domain.PromoRuleDsl;
import com.example.marketing.discount.domain.RuleType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在线参数必须真的被引擎消费——不是只挂一份清单在后台里好看。
 *
 * <p>三条规则同标签、非互斥、各自可叠加：默认 5 条上限时全部命中，
 * 把 {@code discount.max-rules-per-order} 在线压到 1，就只剩一条。
 * 这一条断言不成立就说明声明与接线漂移了。</p>
 */
class PromoEngineOnlineLimitTest {

    private static PromoRuleDsl rule(int i) {
        PromoRuleDsl dsl = new PromoRuleDsl();
        dsl.setRuleNo("PR-BUSY-" + i);
        dsl.setName("叠加测试规则" + i);
        dsl.setType(RuleType.FULL_REDUCTION);
        dsl.setRequiredTags(new HashSet<>(Set.of("A")));
        dsl.setThreshold(new BigDecimal(10));
        dsl.setDiscountValue(new BigDecimal(5));
        dsl.setPriority(i);
        return dsl;
    }

    private static ConfigValues online(String key, String value) {
        ConfigValues values = new ConfigValues(new ConfigSchemaRegistry(
                List.of(new DiscountConfigDefinitions())), new SimpleMeterRegistry());
        values.apply(new ConfigSnapshot(1L, "now",
                Map.of(key, new ConfigSnapshot.Entry(value, ConfigType.INT, 0L))));
        return values;
    }

    private CalcResult run(ConfigValues values) {
        PromoEngine engine = new PromoEngine(new DiscountProperties(), values);
        RuleSnapshot snapshot = RuleSnapshot.build(1L, List.of(rule(1), rule(2), rule(3)));
        CalcItem item = new CalcItem("L1", 1L, 1L, Set.of("A"), new BigDecimal("100.00"), 1);
        CalcResult result = engine.calculate(new CalcInput(9L, null, Set.of("A"), List.of(item)), snapshot);
        assertTrue(!result.isDegraded(), "测试路径不该走降级");
        return result;
    }

    @Test
    @DisplayName("默认上限 5：三条非互斥规则全部叠加")
    void defaultLimitAllowsAllThree() {
        assertEquals(3, run(ConfigValues.empty()).getAppliedRules().size());
    }

    @Test
    @DisplayName("在线把上限压到 1：引擎真的只应用一条")
    void onlineLimitIsHonoured() {
        assertEquals(1, run(online(DiscountConfigDefinitions.MAX_RULES_PER_ORDER, "1"))
                .getAppliedRules().size());
    }

    @Test
    @DisplayName("在线值越界（0 条）时退回出厂值，而不是让整单算不出优惠")
    void outOfRangeFallsBackToDefault() {
        assertEquals(3, run(online(DiscountConfigDefinitions.MAX_RULES_PER_ORDER, "0"))
                .getAppliedRules().size());
    }
}
