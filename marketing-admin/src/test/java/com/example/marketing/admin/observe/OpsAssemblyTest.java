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
    @DisplayName("缺省是 proxy，且只有一个 MetricSource")
    void defaultsToProxy() {
        runner().withPropertyValues(
                "marketing.admin.ops.targets.marketing-activity=marketing-activity:8081",
                "marketing.admin.ops.targets.marketing-gateway=marketing-gateway:8090")
                .run(ctx -> {
                    MetricSource source = ctx.getBean(MetricSource.class);
                    assertEquals("proxy", source.mode());
                    assertTrue(source.serves("marketing-activity"));
                    assertEquals(1, ctx.getBeanNamesForType(MetricSource.class).length);
                });
    }

    @Test
    @DisplayName("mode=local 时 proxy 那份不存在（不是'装了但不用'）")
    void localModeHasNoProxy() {
        runner().withPropertyValues("marketing.admin.ops.metrics-mode=local")
                .run(ctx -> {
                    assertEquals("local", ctx.getBean(MetricSource.class).mode());
                    assertEquals(1, ctx.getBeanNamesForType(MetricSource.class).length);
                    assertEquals(0, ctx.getBeanNamesForType(ProxyMeterSource.class).length);
                });
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
    @DisplayName("mode 拼错必须炸，不能静默退回 proxy")
    void unknownModeFailsStartup() {
        runner().withPropertyValues("marketing.admin.ops.metrics-mode=localol")
                .run(ctx -> {
                    assertNotNull(ctx.getStartupFailure(),
                            "拼错的 mode 若静默按 proxy 处理，LITE 就会长出一堆假 error");
                    assertTrue(rootMessage(ctx.getStartupFailure()).contains("localol"),
                            rootMessage(ctx.getStartupFailure()));
                });
    }

    @Test
    @DisplayName("local 模式没有清单也能起（LITE 只有 self）")
    void localModeNeedsNoTargets() {
        runner().withPropertyValues("marketing.admin.ops.metrics-mode=local")
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
