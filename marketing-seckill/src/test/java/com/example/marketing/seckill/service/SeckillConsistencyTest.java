package com.example.marketing.seckill.service;

import com.example.marketing.seckill.config.SeckillProperties;
import com.example.marketing.seckill.infrastructure.entity.SeckillActivityEntity;
import com.example.marketing.seckill.infrastructure.mapper.SeckillActivityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 秒杀分桶自检：恒等式 {@code 分桶余量合计 + 已售 == 总库存}。
 *
 * <p>与冒烟链路 3、{@code reset-demo-data.sh} 同一条式子——同一件事有三处判据，
 * 而它们必须互相印证，不能各写一套。这里不重写第二遍：桶数取自活动行的 {@code buckets} 列。</p>
 */
class SeckillConsistencyTest {

    private SeckillActivityMapper activityMapper;
    private SeckillStockService stockService;
    private SeckillWarmUpService service;

    @BeforeEach
    void setUp() {
        activityMapper = mock(SeckillActivityMapper.class);
        stockService = mock(SeckillStockService.class);
        service = new SeckillWarmUpService(activityMapper, stockService, new SeckillProperties());

        SeckillActivityEntity activity = new SeckillActivityEntity();
        activity.setActivityNo("SK1");
        activity.setTotalStock(100);
        activity.setSoldStock(30);
        activity.setBuckets(16);
        activity.setStatus("ONLINE");
        when(activityMapper.selectList(any())).thenReturn(List.of(activity));
    }

    private List<Long> buckets(int filled, long each) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            out.add(i < filled ? each : -1L);   // -1 = 该桶键缺失（currentBucketStocks 的口径）
        }
        return out;
    }

    @Test
    @DisplayName("16 桶合计等于 100-30 → 0")
    void match() {
        // 6×5 + 10×4 = 70 == total - sold
        when(stockService.currentBucketStocks(anyString(), anyInt()))
                .thenReturn(java.util.stream.IntStream.range(0, 16)
                        .mapToObj(i -> i < 6 ? 5L : 4L).toList());

        assertEquals(0, service.mismatchCount());
    }

    @Test
    @DisplayName("任一桶键缺失 → 算不符（缺失不是余量为 0）")
    void missingBucketCounts() {
        when(stockService.currentBucketStocks(anyString(), anyInt())).thenReturn(buckets(15, 4L));

        assertEquals(1, service.mismatchCount());
    }

    @Test
    @DisplayName("换过 total_stock 没重预热 → 合计偏小，报出来")
    void staleAfterStockEdit() {
        when(stockService.currentBucketStocks(anyString(), anyInt()))
                .thenReturn(java.util.stream.IntStream.range(0, 16)
                        .mapToObj(i -> 3L).toList());   // 合计 48 != 70

        assertEquals(1, service.mismatchCount());
    }

    @Test
    @DisplayName("判定不了 → -1")
    void unknownStaysUnknown() {
        when(stockService.currentBucketStocks(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("redis down"));

        assertEquals(-1, service.mismatchCount());
    }
}
