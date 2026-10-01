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
    private final ActivityGate activityGate = mock(ActivityGate.class);
    private final CouponTemplateService templateService = mock(CouponTemplateService.class);
    private final CouponStockService stockService = mock(CouponStockService.class);
    private final LocalMessageService localMessageService = mock(LocalMessageService.class);
    private final RiskCheckService riskCheckService = mock(RiskCheckService.class);
    private final UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
    private CouponGrantService service;

    @BeforeEach
    void setUp() {
        // 活动闸默认放行（fail-open 语义另有 ActivityGateTest 专测）
        org.mockito.Mockito.doNothing().when(activityGate).checkGrantable(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        service = new CouponGrantService(idempotentExecutor, activityGate, templateService, stockService,
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

        verify(stockService).rollback(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(70001L), org.mockito.ArgumentMatchers.eq(1), anyString());
        verify(localMessageService, never()).publish(anyString(), anyString());
    }

    @Test
    @DisplayName("P1：登记成功后 publish 失败不归还预扣（PENDING 行是补偿链路，10s 内必出券，归还=超发）")
    void publishFailureAfterRecordingKeepsPreDeduction() {
        when(localMessageService.publish(anyString(), anyString()))
                .thenThrow(new RuntimeException("stream down"));
        // recordIfAbsent 默认 mock 返回 0（int 语义）→ boolean false？显式钉住 true，
        // 表达"登记成功"这一前提
        when(localMessageService.recordIfAbsent(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        assertThrows(RuntimeException.class, () -> service.grant(request()));

        verify(stockService, never()).rollback(anyLong(), anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("P1：消息行已存在（!recorded）→ 归还本次重跑多扣的预扣，直接受理成功")
    void replayedRegistrationRollsBackDuplicatePreDeduction() {
        when(localMessageService.recordIfAbsent(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.grant(request()));

        verify(stockService).rollback(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(70001L), org.mockito.ArgumentMatchers.eq(1), anyString());
        verify(localMessageService, never()).publish(anyString(), anyString());
    }

    @Test
    @DisplayName("归还自身失败不吞原始异常（残余差值由 ④ coupon mismatch 暴露）")
    void rollbackFailureDoesNotSwallowOriginal() {
        when(localMessageService.recordIfAbsent(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(stockService).rollback(anyLong(), anyLong(), anyInt(), anyString());

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.grant(request()));

        org.junit.jupiter.api.Assertions.assertEquals("db down", e.getMessage(),
                "必须抛原始异常，补偿失败只该出现在日志与计数里");
    }

    @Test
    @DisplayName("正常路径不触发归还（rollback 只属于失败路径）")
    void happyPathDoesNotRollBack() {
        // 新实现依赖登记返回值区分首登/重放：happy path = 首次登记成功
        when(localMessageService.recordIfAbsent(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.grant(request()));

        verify(stockService, never()).rollback(anyLong(), anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("W2.5：无券且幂等终态 FAILED → 返回失败终态（不再永远 PROCESSING）")
    void queryResultReturnsFailedTerminal() {
        when(userCouponMapper.selectOne(any())).thenReturn(null);
        when(idempotentExecutor.failureReasonOf(anyString()))
                .thenReturn(java.util.Optional.of("消息超过最大重试次数转 FAILED"));

        com.example.marketing.coupon.dto.GrantResultVO vo = service.queryResult("REQ-DEAD", 70001L);

        org.junit.jupiter.api.Assertions.assertEquals("FAILED", vo.status(), "死信后必须有负向终态信号");
        org.junit.jupiter.api.Assertions.assertEquals("消息超过最大重试次数转 FAILED", vo.message());
    }

    @Test
    @DisplayName("W2.5：无券且非 FAILED（SUCCESS 在途/无行）→ 照旧 PROCESSING")
    void queryResultStaysProcessingWhenNotFailed() {
        when(userCouponMapper.selectOne(any())).thenReturn(null);
        when(idempotentExecutor.failureReasonOf(anyString())).thenReturn(java.util.Optional.empty());

        com.example.marketing.coupon.dto.GrantResultVO vo = service.queryResult("REQ-ALIVE", 70001L);

        org.junit.jupiter.api.Assertions.assertEquals("PROCESSING", vo.status());
    }
}
