package com.example.marketing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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

    /** 必须被扫描到的四个业务模块 + 管理后台 + 消费者账号 + standalone 自身 */
    private static final List<String> REQUIRED_PACKAGES = List.of(
            "com.example.marketing.activity",
            "com.example.marketing.coupon",
            "com.example.marketing.discount",
            "com.example.marketing.seckill",
            "com.example.marketing.admin",
            "com.example.marketing.account",
            "com.example.marketing.standalone");

    /**
     * 被聚进本进程的模块在启动时<b>强制要求</b>的属性。
     *
     * <p>为什么单独立一条断言：这些模块自己的 application.yml 在 standalone 里根本不会被加载
     * （一个应用只有一份配置文件），所以属性必须在 standalone 的 yml 里重述一遍。
     * 漏掉的表现分两种，都不是"启动即报错"那么幸运：
     * {@code marketing.account.jwt-secret} 缺失会让 AccountSecurityConfig 拒绝启动（响亮，好），
     * 而 {@code marketing.consumer.token-secret} 缺失会让 ConsumerRequestIdentity
     * 这个条件 bean 安静地不装配，等到有人注入 ConsumerAuthController 时才炸
     * —— 那已经是运行期第一个登录请求。装配期的这条断言把两者都提前到 mvn test。</p>
     */
    private static final List<String> REQUIRED_PROPERTIES = List.of(
            "marketing.admin.jwt-secret",
            "marketing.account.jwt-secret",
            "marketing.consumer.token-secret",
            // ④ 的清单。它挂在 admin 下面，而插入 account/consumer 两块时一个缩进手误
            // 就会把 ops 整段挪到别人名下：YAML 照样解析、单测照样绿、LITE 的运维面静默失明。
            // 断言"解析后仍在这个位置"是这类错唯一的探测器。
            "marketing.admin.ops.targets.marketing-gateway");

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
    @DisplayName("四个业务模块、管理后台与消费者账号都在扫描范围内")
    void scanCoversAllBusinessModules() {
        List<String> scanned = List.of(scannedPackages());
        for (String required : REQUIRED_PACKAGES) {
            assertTrue(scanned.contains(required), "缺少扫描包: " + required);
        }
    }

    @Test
    @DisplayName("被聚合模块启动即需要的属性，必须在 standalone 自己的 yml 里重述")
    void aggregatedModulesRequiredPropertiesAreRestated() throws Exception {
        Map<String, Object> root;
        try (var in = new ClassPathResource("application.yml").getInputStream()) {
            // 这份 yml 是多文档（默认档 + nacos profile），load() 只接受单文档会直接抛错
            root = (Map<String, Object>) new Yaml()
                    .loadAll(new InputStreamReader(in, StandardCharsets.UTF_8)).iterator().next();
        }
        for (String dotted : REQUIRED_PROPERTIES) {
            String[] hops = dotted.split("\\.");
            Object node = root;
            for (String hop : hops) {
                node = node instanceof Map<?, ?> m ? m.get(hop) : null;
                if (node == null) {
                    break;
                }
            }
            assertTrue(node instanceof String s && !s.isBlank(),
                    "standalone 的 application.yml 缺少 " + dotted
                            + "：模块自己的 yml 在本进程里不会被加载，缺配会让 LITE 起不来"
                            + "或让条件 bean 安静地缺席（后者更糟，拖到第一个登录请求才炸）");
        }
    }

    /**
     * 模块自己 yml 里 {@code marketing.account.*} 的全部键。standalone 必须一条不落重述。
     *
     * <p>为什么不能只靠上面那份手写清单：那份清单只覆盖"启动即需要"的属性，而
     * {@code register-ip-limit} / {@code login-ip-limit} / {@code max-fail-count} 这类
     * 是有 Java 默认值的 —— 漏了不报错、也不影响启动，只是那些环境变量在<b>服役档</b>
     * 静默失效：调了没反应，且没有任何地方告诉你没反应。实测就是这样漏过一批
     * （standalone 只重述了 3 条，account 那边有 9 条）。</p>
     *
     * <p>所以这条断言不去枚举"应该有哪些"，而是拿模块 yml 当清单本身比对：
     * 模块以后新加一个可调参数而忘了在 standalone 补，这里就直接红。</p>
     */
    @Test
    @DisplayName("marketing-account 暴露的每个可调参数，LITE 都得有一个能生效的入口")
    void everyAccountKnobIsReachableInLite() throws Exception {
        java.util.Set<String> declared = leafKeys(
                java.nio.file.Path.of("..", "marketing-account", "src", "main", "resources", "application.yml"),
                "marketing", "account");
        java.util.Set<String> restated = leafKeys(
                java.nio.file.Path.of("src", "main", "resources", "application.yml"),
                "marketing", "account");
        assertTrue(!declared.isEmpty(), "没读到 marketing-account 的 yml，这条断言失去意义了");
        for (String key : declared) {
            assertTrue(restated.contains(key),
                    "marketing.account." + key + " 在 marketing-account 的 yml 里有，standalone 没有："
                            + " LITE 下它对应的那份 yml 不会被加载，这个参数只能靠 Java 默认值，"
                            + "环境变量覆写在服役档静默失效");
        }
    }

    /** 取 yml 里某个嵌套段下一层的键名（值必须是标量，再往下钻一层就返回那层的键） */
    private java.util.Set<String> leafKeys(java.nio.file.Path file, String... hops) throws Exception {
        Map<String, Object> node;
        try (var in = java.nio.file.Files.newInputStream(file)) {
            Object cur = new Yaml().loadAll(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .iterator().next();
            for (String hop : hops) {
                cur = cur instanceof Map<?, ?> m ? m.get(hop) : null;
                if (cur == null) {
                    return java.util.Set.of();
                }
            }
            node = (Map<String, Object>) cur;
        }
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        node.forEach((k, v) -> {
            if (v instanceof Map<?, ?>) {
                ((Map<String, Object>) v).keySet().forEach(keys::add);
            } else {
                keys.add(k);
            }
        });
        return keys;
    }
}
