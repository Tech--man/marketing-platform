package com.example.marketing.gateway.config;

import com.example.marketing.common.config.ConfigSchemaRegistry;
import com.example.marketing.common.config.ConfigSnapshot;
import com.example.marketing.common.config.ConfigType;
import com.example.marketing.common.config.ConfigValues;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流阈值的取值来源。钉的是"两档共库"风险的另一半：快照坏/越界时退回本进程 yml 出厂值——
 * 既不能过限，更不能让网关因此拒绝服务。
 */
class RateRuleResolverTest {

    private static final String ROUTE = "seckill-route";

    private static ConfigValues valuesWith(String rawValue) {
        ConfigValues values = new ConfigValues(
                new ConfigSchemaRegistry(List.of(new GatewayConfigDefinitions())), new SimpleMeterRegistry());
        if (rawValue != null) {
            values.apply(new ConfigSnapshot(1L, "now", Map.of(
                    GatewayConfigDefinitions.keyOf(ROUTE),
                    new ConfigSnapshot.Entry(rawValue, ConfigType.INT, 0L))));
        }
        return values;
    }

    private static GatewayProperties.RateRule yml() {
        GatewayProperties.RateRule r = new GatewayProperties.RateRule();
        r.setLimit(200);
        r.setWindowSeconds(1);
        return r;
    }

    @Test
    @DisplayName("快照有合法值时用快照值，窗口仍取 yml")
    void snapshotWinsOnLimitOnly() {
        RateRuleResolver.Outcome o = new RateRuleResolver().resolve(ROUTE, yml(), valuesWith("5"));
        assertTrue(o.fromSnapshot());
        assertFalse(o.ignored());
        assertEquals(5, o.rule().getLimit());
        assertEquals(1, o.rule().getWindowSeconds(), "window-seconds 不参与在线化");
    }

    @Test
    @DisplayName("越界值退回 yml 出厂值并标 ignored（不过限，也不 500）")
    void outOfRangeFallsBackToYml() {
        RateRuleResolver.Outcome o = new RateRuleResolver().resolve(ROUTE, yml(), valuesWith("0"));
        assertFalse(o.fromSnapshot());
        assertTrue(o.ignored());
        assertEquals(200, o.rule().getLimit());
    }

    @Test
    @DisplayName("无在线覆盖时原样返回 yml 那个对象：每请求零分配，也不去改共享 bean")
    void noOverrideReturnsSameInstance() {
        GatewayProperties.RateRule rule = yml();
        RateRuleResolver.Outcome o = new RateRuleResolver().resolve(ROUTE, rule, valuesWith(null));
        assertSame(rule, o.rule());
        assertFalse(o.fromSnapshot());
        assertFalse(o.ignored());
    }

    @Test
    @DisplayName("路由不在 yml map 里 → 完全不限流的现状不变")
    void unknownRouteStillUnlimited() {
        assertNull(new RateRuleResolver().resolve("nope-route", null, valuesWith("5")).rule());
    }

    @Test
    @DisplayName("快照改过值之后，返回的规则不能反过来污染 yml 那个 bean")
    void neverMutatesYmlRule() {
        GatewayProperties.RateRule rule = yml();
        new RateRuleResolver().resolve(ROUTE, rule, valuesWith("5"));
        assertEquals(200, rule.getLimit(), "共享的 @ConfigurationProperties bean 不能被改");
    }
}
