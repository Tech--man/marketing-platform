package com.example.marketing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ComponentScan;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聚合形态的组件扫描边界（装配期回归测试）。
 *
 * <p>背景：standalone 启动类在包根 com.example.marketing 时，组件扫描会把 common 的
 * {@code @AutoConfiguration} 当普通 @Configuration 提前收编，其
 * {@code @ConditionalOnBean(DataSource)} 在数据源注册之前评估为 false，整个自动装配
 * 静默失效——预览栈当时的表现是启动即挂（CouponGrantService 缺 IdempotentExecutor）。
 * 那时只能靠一次完整构建 + 部署（2-3 分钟）才暴露，本测试把它压到秒级。</p>
 */
class StandaloneComponentScanTest {

    /** 必须由自动装配（AutoConfiguration.imports）接管、不能被组件扫描收编的包 */
    private static final List<String> AUTO_CONFIG_PACKAGES = List.of(
            "com.example.marketing.common",
            "com.example.marketing.openapi");

    /** 必须被扫描到的四个业务模块 + 管理后台 + standalone 自身 */
    private static final List<String> REQUIRED_PACKAGES = List.of(
            "com.example.marketing.activity",
            "com.example.marketing.coupon",
            "com.example.marketing.discount",
            "com.example.marketing.seckill",
            "com.example.marketing.admin",
            "com.example.marketing.standalone");

    private static String[] scannedPackages() {
        ComponentScan scan = MarketingStandaloneApplication.class.getAnnotation(ComponentScan.class);
        assertNotNull(scan, "MarketingStandaloneApplication 上应存在显式 @ComponentScan");
        return scan.basePackages().length > 0 ? scan.basePackages() : scan.value();
    }

    @Test
    @DisplayName("扫描范围不得覆盖 common / open-api（会被提前收编而废掉条件装配）")
    void scanMustNotCoverAutoConfiguredPackages() {
        for (String scanned : scannedPackages()) {
            for (String autoConfigPackage : AUTO_CONFIG_PACKAGES) {
                boolean covers = autoConfigPackage.equals(scanned)
                        || autoConfigPackage.startsWith(scanned + ".");
                assertTrue(!covers,
                        "@ComponentScan 的 " + scanned + " 覆盖了 " + autoConfigPackage
                                + "，会让它的 @ConditionalOnBean 条件在依赖注册前评估为 false");
            }
        }
    }

    @Test
    @DisplayName("四个业务模块、管理后台与 standalone 自身都在扫描范围内")
    void scanCoversAllBusinessModules() {
        List<String> scanned = List.of(scannedPackages());
        for (String required : REQUIRED_PACKAGES) {
            assertTrue(scanned.contains(required), "缺少扫描包: " + required);
        }
    }
}
