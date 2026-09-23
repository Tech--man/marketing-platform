package com.example.marketing.common.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配条件矩阵。用 ApplicationContextRunner 而不是真中间件：
 * Lettuce 是"建 bean 不建连接"，所以端口 1 也够用来验证条件与容错。
 *
 * <p>第一条是母版事实 #8 的守卫：网关没有 DataSource，读取侧装配必须照样成立。</p>
 */
class ConfigCommonAutoConfigurationTest {

    private static LettuceConnectionFactory neverConnecting() {
        LettuceConnectionFactory f = new LettuceConnectionFactory("127.0.0.1", 1);
        f.afterPropertiesSet();
        return f;
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(io.micrometer.core.instrument.MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(ConfigDefinitionProvider.class, () -> new ConfigDefinitionProvider() {
                    @Override
                    public String service() {
                        return "unit";
                    }

                    @Override
                    public List<ConfigDefinition> definitions() {
                        return List.of(ConfigDefinition.ofInt("a", 1, 0, 9, "x"));
                    }
                })
                .withConfiguration(AutoConfigurations.of(ConfigCommonAutoConfiguration.class));
    }

    @Test
    @DisplayName("没有 DataSource 也装配 registry 与 ConfigValues（网关那条路）")
    void wiresWithoutDataSource() {
        runner().run(ctx -> {
            assertEquals(1, ctx.getBeanNamesForType(ConfigSchemaRegistry.class).length);
            assertEquals(1, ctx.getBeanNamesForType(ConfigValues.class).length);
            assertEquals(1, ctx.getBean(ConfigSchemaRegistry.class).all().size());
            assertFalse(ctx.getBeanNamesForType(ConfigSnapshotPoller.class).length > 0,
                    "没有 StringRedisTemplate 就不该起轮询线程");
        });
    }

    @Test
    @DisplayName("有 StringRedisTemplate 时装配轮询器；连不上也不炸，只是刷不出值")
    void pollerWiredWithRedis() {
        LettuceConnectionFactory f = neverConnecting();
        try {
            runner().withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(f))
                    .withPropertyValues("marketing.config.form=LITE", "marketing.config.poll-seconds=2")
                    .run(ctx -> {
                        assertEquals(1, ctx.getBeanNamesForType(ConfigSnapshotPoller.class).length);
                        assertEquals(0L, ctx.getBean(ConfigValues.class).appliedVersion());
                    });
        } finally {
            f.destroy();
        }
    }

    @Test
    @DisplayName("进程里已有 ConfigSyncer（网关）时阻塞轮询器让位，不出现两套节拍")
    void backsOffWhenAnotherSyncerPresent() {
        LettuceConnectionFactory f = neverConnecting();
        try {
            runner().withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(f))
                    .withBean(ConfigSyncer.class, () -> new ConfigSyncer() {
                    })
                    .run(ctx -> {
                        assertEquals(1, ctx.getBeanNamesForType(ConfigValues.class).length);
                        assertFalse(ctx.getBeanNamesForType(ConfigSnapshotPoller.class).length > 0,
                                "网关的 classpath 上确实有 StringRedisTemplate bean，靠类型条件挡不住");
                    });
        } finally {
            f.destroy();
        }
    }

    @Test
    @DisplayName("两个 provider 声明同一个键时上下文启动失败（而不是静默覆盖）")
    void duplicateKeyFailsContext() {
        runner().withBean("secondProvider", ConfigDefinitionProvider.class, () -> new ConfigDefinitionProvider() {
            @Override
            public String service() {
                return "unit2";
            }

            @Override
            public List<ConfigDefinition> definitions() {
                return List.of(ConfigDefinition.ofInt("a", 2, 0, 9, "y"));
            }
        }).run(ctx -> {
            // registry 是懒解析的：起不来的证据可能在 getBean 时才抛出，两条路都要真断到
            Throwable failure = ctx.getStartupFailure();
            if (failure != null) {
                assertTrue(hasIllegalStateCause(failure), "根因应是重复键检查: " + failure);
                return;
            }
            assertThrows(IllegalStateException.class, () -> ctx.getBean(ConfigSchemaRegistry.class));
        });
    }

    private static boolean hasIllegalStateCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof IllegalStateException) {
                return true;
            }
        }
        return false;
    }
}
