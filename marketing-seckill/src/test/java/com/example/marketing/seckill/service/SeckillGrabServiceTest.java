package com.example.marketing.seckill.service;

import com.example.marketing.common.message.LocalMessageService;
import com.example.marketing.openapi.risk.RiskCheckService;
import com.example.marketing.seckill.config.SeckillProperties;
import com.example.marketing.seckill.infrastructure.entity.SeckillActivityEntity;
import com.example.marketing.seckill.infrastructure.mapper.SeckillActivityMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A4 回归（2026-09-29 审查，根因 A）：抢购占名额之后的失败必须回补名额。
 *
 * <p>原实现里 grab 之后的消息登记/投递没有补偿——名额泄漏 + 用户被 bought 标记
 * 锁死至 TTL（轮询永远 ACCEPTED→过期，拿不到单也抢不了第二次）。补偿 = refill
 * 原桶（内部顺带删防重标记）+ 写 FAIL 终态让轮询落地。</p>
 */
class SeckillGrabServiceTest {

    private final SeckillActivityMapper activityMapper = mock(SeckillActivityMapper.class);
    private final SeckillStockService stockService = mock(SeckillStockService.class);
    private final LocalMessageService localMessageService = mock(LocalMessageService.class);
    private final RiskCheckService riskCheckService = mock(RiskCheckService.class);
    private SeckillGrabService service;

    @BeforeEach
    void setUp() {
        service = new SeckillGrabService(activityMapper, stockService, localMessageService,
                riskCheckService, new SeckillProperties(), new SimpleMeterRegistry());

        SeckillActivityEntity activity = new SeckillActivityEntity();
        activity.setActivityNo("SK1");
        activity.setItemId(1L);
        activity.setBuckets(16);
        activity.setStatus("ONLINE");
        when(activityMapper.selectOne(any())).thenReturn(activity);
        when(riskCheckService.check(anyString(), anyLong(), anyString()))
                .thenReturn(RiskCheckService.RiskCheckResult.allow());
        when(stockService.grab(anyString(), anyLong(), anyInt())).thenReturn(3);
    }

    @Test
    @DisplayName("占名额成功但消息登记失败 → refill 回补原桶 + 写 FAIL 终态 + 原异常上抛")
    void recordFailureRefillsBucket() {
        when(localMessageService.recordIfAbsent(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> service.grab("SK1", 70001L));

        verify(stockService).refill(eq("SK1"), eq(70001L), eq(3), eq(16));
        verify(stockService).saveResult(anyString(), eq("FAIL:GRAB_ABORTED"));
    }

    @Test
    @DisplayName("回补自身失败不吞原始异常（残余由 ④ seckill mismatch 暴露）")
    void refillFailureDoesNotSwallowOriginal() {
        when(localMessageService.recordIfAbsent(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(stockService).refill(anyString(), anyLong(), anyInt(), anyInt());

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.grab("SK1", 70001L));

        org.junit.jupiter.api.Assertions.assertEquals("db down", e.getMessage());
    }

    @Test
    @DisplayName("正常路径不触发回补")
    void happyPathDoesNotRefill() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.grab("SK1", 70001L));

        verify(stockService, never()).refill(anyString(), anyLong(), anyInt(), anyInt());
        verify(stockService, never()).saveResult(anyString(), eq("FAIL:GRAB_ABORTED"));
    }
}
