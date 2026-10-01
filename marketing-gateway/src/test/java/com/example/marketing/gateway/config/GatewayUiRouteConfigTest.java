package com.example.marketing.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⑥ 的路由面探针。为什么读 yml 而不是起上下文：
 *
 * <p>③/⑤ 留下的教训是"local 段改了、nacos 段没改"这种病<b>只在升档之后才暴露</b>，
 * 而一次 {@code ApplicationContextRunner} 只装配一个 profile——它天生看不见
 * "两段都写了没有"这件事。所以这里按 {@code ---} 拆文档逐段断言，
 * 探针的形状必须和病的形状一致。</p>
 *
 * <p>另外两条（白名单、限流桶）是同一类：它们错了都不会报错，
 * 只会让 {@code /ui} 要么被当成 C 端路径要 token，要么完全不限流
 * （{@code RateLimitFilter} 的语义是"route 不在 map 里 = 放行"）。</p>
 */
class GatewayUiRouteConfigTest {

    private static final List<Map<String, Object>> DOCUMENTS = documents();

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documents() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var in = new ClassPathResource("application.yml").getInputStream()) {
            for (Object doc : new Yaml().loadAll(in)) {
                if (doc instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读不到网关的 application.yml", e);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> routesOf(Map<String, Object> doc) {
        return (List<Map<String, Object>>) ((Map<String, Object>)
                ((Map<String, Object>) ((Map<String, Object>) doc.get("spring")).get("cloud")).get("gateway")
        ).get("routes");
    }

    private static boolean hasUiRoute(List<Map<String, Object>> routes) {
        return routes.stream().anyMatch(r -> "ui-route".equals(r.get("id"))
                && String.valueOf(r.get("predicates")).contains("/ui/**"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> gatewaySection() {
        Map<String, Object> mkt = (Map<String, Object>) DOCUMENTS.get(0).get("marketing");
        return (Map<String, Object>) mkt.get("gateway");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> consumerSection() {
        return (Map<String, Object>) gatewaySection().get("consumer");
    }

    @Test
    void 探针的分段依据仍然成立_yml还是两段且第二段是nacos() {
        // 这条不是凑数：上面三条断言全部按"第 0 段=local、第 1 段=nacos"取数。
        // 谁加了第三段 profile，这个假设就塌了，必须让探针先红在假设上而不是静默查错段。
        assertEquals(2, DOCUMENTS.size(), "文档数变了：" + DOCUMENTS.size() + " 段，分段依据需要重写");
        assertTrue(String.valueOf(DOCUMENTS.get(1)).contains("nacos"), "第二段不是 nacos 段");
    }

    @Test
    void local与nacos两段都要有ui_route_漏一段就是只在升档后才红() {
        assertTrue(hasUiRoute(routesOf(DOCUMENTS.get(0))), "默认(local)段没有 ui-route");
        assertTrue(hasUiRoute(routesOf(DOCUMENTS.get(1))),
                "nacos 段缺 ui-route：LITE 一切正常，FULL 的 /ui 整片 404");
    }

    @Test
    void 前后端分离后静态路由必须指向WEB容器_指回admin等于绕过nginx() {
        // 分离（2026-10-01）后的契约：ui/h5 走 WEB_HOST/WEB_PORT（nginx 容器），
        // 不再复用 ADMIN 占位符、不再 lb://marketing-admin——admin jar 里已无静态文件，
        // 指回去 = 五套入口整片 404（且只在界面打开时才暴露）。
        for (Map<String, Object> doc : DOCUMENTS) {
            for (String id : List.of("ui-route", "h5-route")) {
                String uri = routesOf(doc).stream()
                        .filter(r -> id.equals(r.get("id")))
                        .map(r -> String.valueOf(r.get("uri"))).findFirst().orElse("");
                assertTrue(uri.contains("WEB_HOST") && uri.contains("WEB_PORT"),
                        id + " 的 uri 应含 WEB_HOST/WEB_PORT 占位符，实际: " + uri);
                assertFalse(uri.contains("ADMIN") || uri.contains("marketing-admin"),
                        id + " 指回了 admin——静态承载已移交 marketing-web：" + uri);
            }
        }
    }

    @Test
    void 静态资源不能被消费者托管清单罩住否则打开界面先得登录() {
        // 旧的写法是断言 /ui/** 在 AuthFilter 的 whitelist 里。AuthFilter 删除后，
        // 同一条保证换了机制：托管清单只罩 /api/**，界面路径天然不在里面。
        // 断言跟着机制走，才不会出现"测试绿着但保证已经不成立"。
        List<?> managed = (List<?>) consumerSection().get("managed-paths");
        org.springframework.util.AntPathMatcher m = new org.springframework.util.AntPathMatcher();
        for (String p : List.of("/ui/index.html", "/h5/index.html", "/ui/assets/x.js")) {
            for (Object pattern : managed) {
                assertFalse(m.match(String.valueOf(pattern), p),
                        p + " 被托管清单的 " + pattern + " 罩住了：后台/H5 页面会要求登录态");
            }
        }
    }

    @Test
    void ui_route必须在限流map里因为不在map里等于完全不限流() {
        Map<String, Object> rl = (Map<String, Object>) gatewaySection().get("rate-limit");
        assertTrue(rl.containsKey("ui-route"),
                "route id 不在 rate-limit map 里 = 不限流且不报错（RateLimitFilter 的语义）");
    }
}
