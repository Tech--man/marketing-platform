package com.example.marketing.coupon.service;

import com.example.marketing.common.idempotent.IdempotentExecutor;
import com.example.marketing.common.message.LocalMessageService;
import com.example.marketing.coupon.dto.GrantRequest;
import com.example.marketing.coupon.infrastructure.entity.CouponTemplateEntity;
import com.example.marketing.coupon.infrastructure.mapper.UserCouponMapper;
import com.example.marketing.openapi.risk.RiskCheckService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A1/A2 回归（2026-09-29 审查，根因 A）：预扣之后的失败必须归还预扣。
 *
 * <p>原实现里 Redis 预扣与消息登记之间没有补偿——幂等键标 FAILED、重试整体重跑
 * action 再扣一次（1 张券吃 2 份库存 + 2 次限领，第二次多半直接 EXCEED_LIMIT，
 * 用户一张都拿不到）；rollback_stock.lua 从写成起就是死代码。本用例把"失败即归还"
 * 钉住。</p>
 */
class CouponGrantServiceTest {

    private final IdempotentExecutor idempotentExecutor = mock(IdempotentExecutor.class);
    private final CouponTemplateService templateService = mock(CouponTemplateService.class);
    private final CouponStockService stockService = mock(CouponStockService.class);
    private final LocalMessageService localMessageService = mock(LocalMessageService.class);
    private final RiskCheckService riskCheckService = mock(RiskCheckService.class);
    private final UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
    private CouponGrantService service;

    @BeforeEach
    void setUp() {
        service = new CouponGrantService(idempotentExecutor, templateService, stockService,
                localMessageService, riskCheckService, userCouponMapper, new SimpleMeterRegistry());
        // 幂等执行器直通 action：本测试只关心 action 内部的补偿编排
        when(idempotentExecutor.execute(anyString(), any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(2)).get());
        when(riskCheckService.check(anyString(), anyLong(), anyString()))
                .thenReturn(RiskCheckService.RiskCheckResult.allow());

        CouponTemplateEntity template = new CouponTemplateEntity();
        template.setId(1L);
        template.setTemplateNo("CT2026001");
        template.setActivityNo("ACT2026001");
        template.setPerUserLimit(5);
        when(templateService.getRequiringGrantable("CT2026001")).thenReturn(template);
        when(stockService.deduct(anyLong(), anyLong(), anyInt(), anyInt(),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class)))
                .thenReturn(CouponStockService.DeductResult.SUCCESS);
    }

    private GrantRequest request() {
        return new GrantRequest("REQ-A1", 70001L, "CT2026001");
    }

    @Test
    @DisplayName("预扣成功但消息登记失败 → rollback 归还预扣 + 原异常上抛（重试才不会二次预扣）")
    void recordFailureRollsBackPreDeduction() {
        when(localMessageService.recordIfAbsent(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> service.grant(request()));

        verify(stockService).rollback(1L, 70001L, 1);
        verify(localMessageService, never()).publish(anyString(), anyString());
    }

    @Test
    @DisplayName("发布失败同样归还（登记成功≠投递成功，补偿链路还没闭环）")
    void publishFailureRollsBackPreDeduction() {
        when(localMessageService.publish(anyString(), anyString()))
                .thenThrow(new RuntimeException("stream down"));

        assertThrows(RuntimeException.class, () -> service.grant(request()));

        verify(stockService).rollback(1L, 70001L, 1);
    }

    @Test
    @DisplayName("归还自身失败不吞原始异常（残余差值由 ④ coupon mismatch 暴露）")
    void rollbackFailureDoesNotSwallowOriginal() {
        when(localMessageService.recordIfAbsent(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(stockService).rollback(anyLong(), anyLong(), anyInt());

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.grant(request()));

        org.junit.jupiter.api.Assertions.assertEquals("db down", e.getMessage(),
                "必须抛原始异常，补偿失败只该出现在日志与计数里");
    }

    @Test
    @DisplayName("正常路径不触发归还（rollback 只属于失败路径）")
    void happyPathDoesNotRollBack() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.grant(request()));

        verify(stockService, never()).rollback(anyLong(), anyLong(), anyInt());
    }
}
