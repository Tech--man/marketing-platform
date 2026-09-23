package com.example.marketing.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * local 与 nacos 两套 profile 的路由 id 必须同集合，且每条路由都要有 rate-limit 条目。
 *
 * <p>两条都是"看着没事、换形态就全红"的那类：漏一条 lb:// 路由，LITE 一切正常而 FULL
 * 整片 404（①② 就这么栽过一次）；route 不在 rate-limit map 里等于完全不限流
 * （RateLimitFilter 的现行语义），于是新增路由很容易变成一条不限流的暗道。</p>
 */
class GatewayRouteTableTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documents() throws Exception {
        try (var in = new ClassPathResource("application.yml").getInputStream()) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object doc : new Yaml().loadAll(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                out.add((Map<String, Object>) doc);
            }
            return out;
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> routeIds(Map<String, Object> doc) {
        Set<String> ids = new HashSet<>();
        if (doc == null) {
            return ids;
        }
        Map<String, Object> spring = (Map<String, Object>) doc.get("spring");
        if (spring == null) {
            return ids;
        }
        Map<String, Object> cloud = (Map<String, Object>) spring.get("cloud");
        Map<String, Object> gateway = cloud == null ? null : (Map<String, Object>) cloud.get("gateway");
        List<Map<String, Object>> routes = gateway == null ? null : (List<Map<String, Object>>) gateway.get("routes");
        if (routes != null) {
            routes.forEach(r -> ids.add(String.valueOf(r.get("id"))));
        }
        return ids;
    }

    @Test
    @DisplayName("两套 profile 的路由 id 同集合")
    void bothProfilesDeclareTheSameRoutes() throws Exception {
        List<Map<String, Object>> docs = documents();
        assertTrue(docs.size() >= 2, "application.yml 应有 local 与 nacos 两个文档");
        Set<String> local = routeIds(docs.get(0));
        Set<String> nacos = routeIds(docs.get(1));
        assertTrue(local.size() >= 5, "路由数不对劲: " + local);
        assertEquals(local, nacos, "local 与 nacos 的路由清单漂移了");
    }

    @Test
    @DisplayName("每条路由都有 rate-limit 条目（没有=不限流）")
    void everyRouteHasRateLimitRule() throws Exception {
        Map<String, Object> marketing = (Map<String, Object>) documents().get(0).get("marketing");
        Map<String, Object> gateway = (Map<String, Object>) marketing.get("gateway");
        Map<String, Object> rateLimit = (Map<String, Object>) gateway.get("rate-limit");
        for (String id : routeIds(documents().get(0))) {
            assertTrue(rateLimit.containsKey(id), "路由 " + id + " 没有 rate-limit 条目 = 不限流");
        }
    }
}
