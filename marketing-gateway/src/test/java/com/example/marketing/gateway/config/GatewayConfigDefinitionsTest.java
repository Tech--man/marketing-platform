package com.example.marketing.gateway.config;

import com.example.marketing.common.config.ConfigDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 声明清单与 application.yml 的限流条目必须一一对上。
 *
 * <p>漂移的两种后果都很难看：yml 有路由而声明没有 → 后台改不动它（运营以为改了就完事）；
 * 声明有而 yml 没这条路由 → 一个不存在的桶挂在清单里。</p>
 */
class GatewayConfigDefinitionsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstDocument() throws Exception {
        try (var in = new ClassPathResource("application.yml").getInputStream()) {
            // 这份 yml 是多文档（local + nacos 两套 profile），load() 只接受单文档会直接抛错
            return (Map<String, Object>) new Yaml()
                    .loadAll(new InputStreamReader(in, StandardCharsets.UTF_8)).iterator().next();
        }
    }

    @Test
    @DisplayName("yml 的 rate-limit 条目与在线声明清单严格一一对应")
    void definitionsMatchYamlRateLimitMap() throws Exception {
        Map<String, Object> marketing = (Map<String, Object>) firstDocument().get("marketing");
        Map<String, Object> gateway = (Map<String, Object>) marketing.get("gateway");
        Map<String, Object> rateLimit = (Map<String, Object>) gateway.get("rate-limit");
        Set<String> yamlKeys = rateLimit.keySet().stream()
                .map(k -> GatewayConfigDefinitions.keyOf(String.valueOf(k)))
                .collect(Collectors.toSet());
        Set<String> declaredKeys = new GatewayConfigDefinitions().definitions().stream()
                .map(ConfigDefinition::key).collect(Collectors.toSet());
        assertEquals(yamlKeys, declaredKeys, "yml 的 rate-limit 条目与声明清单漂移了");
    }

    @Test
    @DisplayName("每条声明的出厂值必须落在自己的边界内，且服务名与 spring.application.name 一致")
    void defaultsAreWithinOwnBounds() {
        GatewayConfigDefinitions provider = new GatewayConfigDefinitions();
        assertEquals("marketing-gateway", provider.service());
        List<ConfigDefinition> defs = provider.definitions();
        assertTrue(defs.size() >= 5);
        for (ConfigDefinition d : defs) {
            assertTrue(d.accepts(d.defaultValue()), d.key() + " 的出厂值不自洽: " + d.defaultValue());
        }
    }
}
