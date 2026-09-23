package com.example.marketing.activity.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 灰度判定本身。规则来源从 yml 换成 DB 列之后，这些语义必须一条不变——
 * 尤其"未配规则=全量放行"，它是链路 0 的断言。
 */
class GrayServiceTest {

    private static GrayService with(String activityNo, Optional<GrayRuleCache.Rule> rule) {
        GrayRuleCache cache = mock(GrayRuleCache.class);
        when(cache.rule(anyString())).thenReturn(Optional.empty());
        when(cache.rule(activityNo)).thenReturn(rule);
        return new GrayService(cache);
    }

    @Test
    @DisplayName("未配灰度 = 全量放行")
    void noRuleMeansFullTraffic() {
        assertTrue(with("A1", Optional.empty()).hit("A1", 70001L));
    }

    @Test
    @DisplayName("percent=0 一个都不放，除非在白名单里")
    void zeroPercentBlocksEveryoneButWhitelist() {
        GrayService s = with("A1", Optional.of(new GrayRuleCache.Rule(0, Set.of(999L))));
        assertFalse(s.hit("A1", 70001L));
        assertTrue(s.hit("A1", 999L), "白名单要能穿透 0% —— 内测与压测账号靠它");
    }

    @Test
    @DisplayName("percent=5 按 userId 取模命中，同一用户结果稳定")
    void percentModuloIsStable() {
        GrayService s = with("A1", Optional.of(new GrayRuleCache.Rule(5, Set.of())));
        assertTrue(s.hit("A1", 70001L), "70001 % 100 = 1 < 5");
        assertFalse(s.hit("A1", 70050L), "70050 % 100 = 50 >= 5");
        assertTrue(s.hit("A1", 101L));
    }

    @Test
    @DisplayName("percent=100 全量命中（种子 ACT2026001 就是这个状态）")
    void hundredPercentHitsEveryone() {
        GrayService s = with("ACT2026001", Optional.of(new GrayRuleCache.Rule(100, Set.of())));
        assertTrue(s.hit("ACT2026001", 70001L));
        assertTrue(s.hit("ACT2026001", 70050L));
    }

    @Test
    @DisplayName("userId 缺失按 0 处理（未登录也要有确定答案）")
    void nullUserIdUsesZero() {
        assertTrue(with("A1", Optional.of(new GrayRuleCache.Rule(5, Set.of()))).hit("A1", null));
        assertFalse(with("A1", Optional.of(new GrayRuleCache.Rule(0, Set.of()))).hit("A1", null));
    }
}
