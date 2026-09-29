package com.example.marketing.discount.service;

import com.example.marketing.discount.config.DiscountProperties;
import com.example.marketing.discount.engine.RuleSnapshot;
import com.example.marketing.discount.infrastructure.mapper.PromoRuleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 规则缓存：冷路径并发重建合并（Redis 无版本号 key 时 version 恒为 0）。
 */
class RuleCacheManagerTest {

    /**
     * Mapper 桩：BaseMapper 抽象方法过多，用动态代理只拦 selectList 并统计回源次数。
     * StringRedisTemplate 传 null，读版本号必然抛异常 → 走 Redis 不可用分支（version 视作 0）。
     */
    private static PromoRuleMapper countingMapper(long queryMillis, AtomicInteger queries) {
        return (PromoRuleMapper) Proxy.newProxyInstance(
                PromoRuleMapper.class.getClassLoader(),
                new Class<?>[]{PromoRuleMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        queries.incrementAndGet();
                        Thread.sleep(queryMillis);
                        return List.of();
                    }
                    return null;
                });
    }

    @Test
    @DisplayName("并发冷请求只回源一次 DB，其余线程复用等到的快照")
    void concurrentColdRequestsCoalesceIntoOneRebuild() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        RuleCacheManager manager = new RuleCacheManager(
                countingMapper(120, queries), null, new DiscountProperties());

        int threads = 4;
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        RuleSnapshot[] got = new RuleSnapshot[threads];
        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                pool.submit(() -> {
                    gate.await();
                    got[index] = manager.snapshot();
                    return null;
                });
            }
            gate.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, queries.get(), "只允许一次 DB 回源");
        for (RuleSnapshot snapshot : got) {
            assertSame(got[0], snapshot);
        }
    }

    @Test
    @DisplayName("H8：bump 用 Redis INCR 单调推进，不再写毫秒时间戳（同毫秒撞号=变更永久丢失）")
    void bumpUsesIncrNotTimestamp() {
        PromoRuleMapper mapper = countingMapper(0, new AtomicInteger());
        org.springframework.data.redis.core.StringRedisTemplate redis =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> ops =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("discount:rule:version")).thenReturn(7L);
        RuleCacheManager manager = new RuleCacheManager(mapper, redis, new DiscountProperties());

        long version = manager.bumpVersion();

        org.mockito.Mockito.verify(ops).increment("discount:rule:version");
        org.mockito.Mockito.verify(ops, org.mockito.Mockito.never())
                .set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        assertEquals(7L, version, "版本号必须是 INCR 的返回值，不该再出现时间戳");
    }

    @Test
    @DisplayName("H8：bump 写 Redis 失败 → 本实例立即按 DB 重建（旧实现只打日志、永不自愈）")
    void bumpFailureRebuildsFromDbImmediately() {
        AtomicInteger queries = new AtomicInteger();
        org.springframework.data.redis.core.StringRedisTemplate redis =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> ops =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        // INCR 与后续的版本号回读全部失败（Redis 整体不可用）
        when(ops.increment("discount:rule:version")).thenThrow(new RuntimeException("redis down"));
        when(ops.get("discount:rule:version")).thenThrow(new RuntimeException("redis down"));
        RuleCacheManager manager = new RuleCacheManager(
                countingMapper(0, queries), redis, new DiscountProperties());

        manager.bumpVersion();

        assertEquals(1, queries.get(), "bump 失败必须触发一次 DB 重建，而不是只用旧快照");
    }
}
