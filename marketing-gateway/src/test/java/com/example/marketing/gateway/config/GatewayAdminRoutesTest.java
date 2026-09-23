package com.example.marketing.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ③ 新增的四条后台前缀：local 与 nacos 两套 profile 都要有路由，且每条都要有 rate-limit 项。
 *
 * <p>断言读 {@code application.yml} 原文而不是 java 常量：会漏的恰好是 yml 那一侧
 * （只改 local 忘了 nacos，注册中心形态下后台整片 404，而 LITE 一切正常），
 * 用常量自证等于没测。</p>
 */
class GatewayAdminRoutesTest {

    private static final List<String> IDS = List.of(
            "admin-activity-route", "admin-coupon-route", "admin-discount-route", "admin-seckill-route");
    private static final List<String> PATHS = List.of(
            "/api/admin/activities", "/api/admin/coupon", "/api/admin/discount", "/api/admin/seckill");

    private static final String YML = readYml();

    private static String readYml() {
        try (var in = new ClassPathResource("application.yml").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int count(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    @DisplayName("四条前缀在 local 与 nacos 两段都配了（各一次 = 共两次）")
    void everyPrefixRoutedInBothProfiles() {
        for (String path : PATHS) {
            assertTrue(count(YML, "Path=" + path + "/**") >= 2,
                    path + " 的路由少于两处（local 与 nacos 各一处），注册中心形态会 404");
        }
    }

    @Test
    @DisplayName("四个 route id 都进了 rate-limit map：不在 map 里=完全不限流且不报错")
    void everyNewRouteHasRateLimit() {
        for (String id : IDS) {
            assertTrue(count(YML, "      " + id + ":") >= 1, id + " 没进 marketing.gateway.rate-limit map");
        }
    }

    @Test
    @DisplayName("限流项必须是 {limit, window-seconds} 的形状（写成裸标量会被 binder 静默丢掉）")
    void rateLimitEntriesHaveSameShapeAsExistingOnes() {
        for (String id : IDS) {
            int at = YML.indexOf("      " + id + ":");
            assertTrue(at > 0 && YML.substring(at, Math.min(at + 120, YML.length())).contains("limit:"),
                    id + " 的限流项缺 limit: 子键");
        }
    }

    @Test
    @DisplayName("每个 profile 段里，四条新路由都排在 admin-route 之前（网关按声明顺序取第一个命中）")
    void newRoutesDeclaredBeforeCatchAllAdminRouteInEachSection() {
        // 只看配了路由的段（local 段与 nacos 段），逐段判顺序：
        // 全文 indexOf 只会验到第一次出现，nacos 段里排错了照样绿——那正是本段最容易漏的形态差
        int sectionsChecked = 0;
        for (String section : YML.split("\n---")) {
            int admin = section.indexOf("- id: admin-route");
            if (admin < 0) {
                continue;
            }
            sectionsChecked++;
            for (String id : IDS) {
                int at = section.indexOf("- id: " + id);
                assertTrue(at > 0 && at < admin,
                        id + " 必须声明在本段 admin-route 之前，否则会被 /api/admin/** 整片吞掉");
            }
        }
        assertTrue(sectionsChecked >= 2,
                "至少要在两个 profile 段里验到顺序（local 与 nacos），实际验到 " + sectionsChecked);
    }
}
