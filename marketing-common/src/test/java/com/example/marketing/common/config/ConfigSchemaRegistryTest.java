package com.example.marketing.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一个键两处声明 = 两套真相，必须启动期失败。
 * 把 putIfAbsent 改成 put 之后第一条必须红，否则这条测试是空的。
 */
class ConfigSchemaRegistryTest {

    private static ConfigDefinitionProvider provider(String service, ConfigDefinition... defs) {
        return new ConfigDefinitionProvider() {
            @Override
            public String service() {
                return service;
            }

            @Override
            public List<ConfigDefinition> definitions() {
                return List.of(defs);
            }
        };
    }

    @Test
    @DisplayName("同键重复声明在构造期抛错，并点名键与先来者")
    void duplicateKeyFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ConfigSchemaRegistry(List.of(
                        provider("gateway", ConfigDefinition.ofInt("a", 1, 0, 10, "x")),
                        provider("admin", ConfigDefinition.ofInt("a", 2, 0, 10, "y")))));
        assertTrue(e.getMessage().contains("a"));
        assertTrue(e.getMessage().contains("gateway"));
    }

    @Test
    @DisplayName("find/declares/serviceByKey 与声明一致")
    void collectsAcrossProviders() {
        ConfigSchemaRegistry registry = new ConfigSchemaRegistry(List.of(
                provider("gateway", ConfigDefinition.ofInt("a", 1, 0, 10, "x")),
                provider("seckill", ConfigDefinition.ofLong("b", 1L, 0L, 10L, "y"))));
        assertEquals(2, registry.all().size());
        assertEquals("seckill", registry.serviceByKey().get("b"));
        assertEquals(ConfigType.INT, registry.find("a").orElseThrow().type());
        assertFalse(registry.declares("c"));
        assertTrue(registry.declares("b"));
    }

    @Test
    @DisplayName("empty() 给出一张没有声明的表，ConfigValues.empty() 走它")
    void emptyRegistryHasNoKeys() {
        assertTrue(ConfigSchemaRegistry.empty().all().isEmpty());
        assertEquals(5, ConfigValues.empty().intOr("anything", 5));
    }
}
