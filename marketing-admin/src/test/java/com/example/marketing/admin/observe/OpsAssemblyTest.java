package com.example.marketing.admin.observe;

import com.example.marketing.admin.config.OpsObservabilityConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标源二选一，不是二都装。
 *
 * <p>同时装两个的后果很具体：LITE 下 proxy 那份会去抓 8081-8084，那里<b>根本没有进程</b>
 * （四个模块与后台同在 standalone 一个 JVM），于是大盘上多出四条假 error，
 * 而"某个 target 抓不到"正是 ④ 要人当回事的信号——把它变成常态噪音等于把这套诊断废了。</p>
 *
 * <p>装配回归用 {@link ApplicationContextRunner}（本仓既有做法）：这类问题只有在真容器里
 * 才暴露，mock 掉依赖就看不见（⑤ 有个 bean 顺序 bug 就是这么漏过 5 个装配测试、
 * 最后让 LITE 起不来的）。</p>
 */
class OpsAssemblyTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(MeterRegistry.class, () -> new PrometheusMeterRegistry(PrometheusConfig.DEFAULT))
                // opsStreamDepth 要这两样：真实装配里由 common 的自动装配给，
                // 裸容器里必须自己补，否则测的是"缺 bean"而不是"模式选对了没"
                .withBean(StringRedisTemplate.class, () -> org.mockito.Mockito.mock(StringRedisTemplate.class))
                .withBean(com.example.marketing.common.cache.CacheReheatRegistry.class,
                        () -> new com.example.marketing.common.cache.CacheReheatRegistry(List.of()))
                .withUserConfiguration(OpsObservabilityConfig.class);
    }

    @Test
    @DisplayName("LITE 形态：self 走本地，网关仍走 HTTP（一条盘里两种源并存）")
    void localAndProxyCoexist() {
        runner().withPropertyValues(
                "marketing.admin.ops.targets.self=127.0.0.1:8085",
                "marketing.admin.ops.targets.marketing-gateway=marketing-gateway:8091",
                "marketing.admin.ops.local-targets[0]=self")
                .run(ctx -> {
                    TargetSources sources = ctx.getBean(TargetSources.class);
                    assertEquals("local", sources.sourceOf("self"));
                    assertEquals("proxy", sources.sourceOf("marketing-gateway"),
                            "网关在每种形态下都是独立进程，LITE 也必须抓它");
                });
    }

    @Test
    @DisplayName("FULL 形态：local-targets 留空 = 全部走 HTTP")
    void fullModeIsAllProxy() {
        runner().withPropertyValues(
                "marketing.admin.ops.targets.marketing-activity=marketing-activity:8081")
                .run(ctx -> assertEquals("proxy",
                        ctx.getBean(TargetSources.class).sourceOf("marketing-activity")));
    }

    @Test
    @DisplayName("清单里一条不合规就让启动失败，并点名是哪条")
    void badTargetFailsStartup() {
        runner().withPropertyValues(
                "marketing.admin.ops.targets.marketing-activity=marketing-activity:8081",
                "marketing.admin.ops.targets.oops=127.0.0.1:3307")
                .run(ctx -> {
                    assertNotNull(ctx.getStartupFailure(), "SSRF 白名单外的配置必须让启动失败");
                    assertTrue(rootMessage(ctx.getStartupFailure()).contains("oops"),
                            "报错要点名是哪个配置项: " + rootMessage(ctx.getStartupFailure()));
                });
    }

    @Test
    @DisplayName("proxy 模式但清单为空 = 什么都读不到，宁可不启动")
    void emptyTargetsInProxyModeFail() {
        runner().run(ctx -> assertNotNull(ctx.getStartupFailure(),
                "空清单会让整个只读面静默变成\"什么都读不到\""));
    }

    @Test
    @DisplayName("local-targets 里的名字不在清单内 → 启动失败（否则它静默走 proxy 并永远抓不到）")
    void unknownLocalTargetFailsStartup() {
        runner().withPropertyValues(
                "marketing.admin.ops.targets.self=127.0.0.1:8085",
                "marketing.admin.ops.local-targets[0]=marketing-activity")
                .run(ctx -> {
                    assertNotNull(ctx.getStartupFailure(), "名字漂了必须炸");
                    assertTrue(rootMessage(ctx.getStartupFailure()).contains("marketing-activity"),
                            rootMessage(ctx.getStartupFailure()));
                });
    }

    @Test
    @DisplayName("清单齐备就能起（LITE 的最小配置：self + 网关）")
    void minimalLiteConfigStarts() {
        runner().withPropertyValues(
                "marketing.admin.ops.targets.self=127.0.0.1:8085",
                "marketing.admin.ops.targets.marketing-gateway=marketing-gateway:8091",
                "marketing.admin.ops.local-targets[0]=self")
                .run(ctx -> assertNull(ctx.getStartupFailure()));
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
