package com.example.marketing.seckill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 秒杀分桶规划与 Redis 键命名（纯函数部分，不依赖真实 Redis）。
 */
class SeckillStockServiceTest {

    private final SeckillStockService service = new SeckillStockService(null,
            new com.example.marketing.seckill.config.SeckillRuntimeConfig(
                    com.example.marketing.common.config.ConfigValues.empty(),
                    new com.example.marketing.seckill.config.SeckillProperties()));

    @Test
    @DisplayName("整除分配：每桶均等")
    void evenAllocation() {
        List<Integer> plan = service.allocateBuckets(160, 16);
        assertEquals(16, plan.size());
        assertTrue(plan.stream().allMatch(q -> q == 10));
        assertEquals(160, plan.stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    @DisplayName("余数摊给前几个桶，总量守恒")
    void remainderSpreadToFront() {
        List<Integer> plan = service.allocateBuckets(200, 16);
        // 200 = 12*16 + 8：前 8 桶 13 件，后 8 桶 12 件
        assertEquals(200, plan.stream().mapToInt(Integer::intValue).sum());
        assertEquals(13, plan.get(0));
        assertEquals(13, plan.get(7));
        assertEquals(12, plan.get(8));
        assertEquals(12, plan.get(15));
    }

    @Test
    @DisplayName("库存少于桶数：每桶至多 1 件，多余桶为 0 仍参与借桶遍历")
    void sparseStock() {
        List<Integer> plan = service.allocateBuckets(3, 16);
        assertEquals(3, plan.stream().mapToInt(Integer::intValue).sum());
        assertEquals(1, plan.get(0));
        assertEquals(0, plan.get(3));
    }

    @Test
    @DisplayName("键命名与桶号 1-based 约定")
    void keyNaming() {
        assertEquals("seckill:stock:SK1:1", service.stockKey("SK1", 1));
        assertEquals("seckill:bought:SK1:42", service.boughtKey("SK1", 42L));
        assertEquals("seckill:result:tk", service.resultKey("tk"));
        assertEquals(List.of("seckill:stock:SK1:1", "seckill:stock:SK1:2"),
                service.keysOf("SK1", 2));
    }
}
