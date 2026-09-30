package com.example.marketing.activity.service;

import com.example.marketing.activity.domain.ActivityEvent;
import com.example.marketing.activity.dto.ActivityView;
import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import com.example.marketing.activity.infrastructure.mapper.ActivityMapper;
import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.cache.CacheReheater;
import com.example.marketing.common.exception.BizException;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台写路径的地雷 A 防守：改了 {@code budget_amount} 而不 force 重建预算键，
 * 键还在（上线预热走 SETNX），于是"C 端余额永远是旧值"——本仓库踩过一次的那条。
 *
 * <p>断言全部打在"外部可见后果"上：调没调 reheat、写没写库、报的是哪个码。</p>
 */
class ActivityAdminWriteTest {

    private final ActivityMapper mapper = mock(ActivityMapper.class);
    private final BudgetService budgetService = mock(BudgetService.class);
    private final ActivityService service = new ActivityService(mapper, budgetService,
                org.mockito.Mockito.mock(ActivityGatePublisher.class));

    private ActivityEntity existing(String budget, int version) {
        ActivityEntity e = new ActivityEntity();
        e.setActivityNo("ACT9001");
        e.setName("冒烟活动");
        e.setStatus("ONLINE");
        e.setBudgetAmount(new BigDecimal(budget));
        e.setVersion(version);
        return e;
    }

    private void stubSelect(ActivityEntity entity) {
        when(mapper.selectOne(any())).thenReturn(entity);
    }

    @Test
    @DisplayName("改预算必须 force 重预热预算键：SETNX 语义下不删键=改动永不生效")
    void budgetUpdateForcesReheat() {
        stubSelect(existing("100.00", 3));
        when(mapper.updateById(any(ActivityEntity.class))).thenReturn(1);
        when(budgetService.reheat(eq("ACT9001"), eq(true)))
                .thenReturn(new CacheReheater.Result("budget", "ACT9001", 10000L, 8000L, "对账口径"));

        ActivityEntity after = service.updateBudget("ACT9001", new BigDecimal("80.00"), 3);

        assertEquals(new BigDecimal("80.00"), after.getBudgetAmount());
        verify(budgetService).reheat("ACT9001", true);
    }

    @Test
    @DisplayName("第六批 W2：RE_ONLINE 预热走 warmCentsIfAbsent(computeRemainCents)——不用全额 warmIfAbsent")
    void reonlineWarmUsesCentsIfAbsentWithReconciliation() {
        ActivityEntity entity = existing("100.00", 2);
        entity.setStatus("OFFLINE");  // 从 OFFLINE 经 PUBLISH → ONLINE 触发预热
        stubSelect(entity);
        when(mapper.updateById(any(ActivityEntity.class))).thenReturn(1);
        when(budgetService.computeRemainCents("ACT9001")).thenReturn(7_000L);

        service.transition("ACT9001", ActivityEvent.RE_ONLINE);

        // 必须是 warmCentsIfAbsent(对账值)——warmIfAbsent(budgetAmount) 是地雷 E 的
        // 旧写法：下线期间丢键再上线，全额会把已消耗的预算凭空回涨
        verify(budgetService).warmCentsIfAbsent("ACT9001", 7_000L);
        verify(budgetService, org.mockito.Mockito.never())
                .warmIfAbsent(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("客户端 version 与库里不一致 → 41008，既不写库也不刷键")
    void staleVersionRejectedBeforeWrite() {
        stubSelect(existing("100.00", 5));
        BizException e = assertThrows(BizException.class,
                () -> service.updateBudget("ACT9001", new BigDecimal("80.00"), 3));
        assertEquals(41008, e.getCode());
        verify(mapper, never()).updateById(any(ActivityEntity.class));
        verify(budgetService, never()).reheat(anyString(), eq(true));
    }

    @Test
    @DisplayName("version 不传也拒（41008）：默认值等于\"我不在乎被别人改过\"")
    void missingVersionRejected() {
        stubSelect(existing("100.00", 5));
        BizException e = assertThrows(BizException.class,
                () -> service.updateBudget("ACT9001", new BigDecimal("80.00"), null));
        assertEquals(41008, e.getCode());
    }

    @Test
    @DisplayName("真并发（@Version 已不匹配，updateById 返回 0）同样是 41008 且不刷键")
    void concurrentUpdateMapsTo41008() {
        stubSelect(existing("100.00", 3));
        when(mapper.updateById(any(ActivityEntity.class))).thenReturn(0);
        BizException e = assertThrows(BizException.class,
                () -> service.updateBudget("ACT9001", new BigDecimal("80.00"), 3));
        assertEquals(41008, e.getCode());
        verify(budgetService, never()).reheat(anyString(), eq(true));
    }

    @Test
    @DisplayName("改灰度不碰预算键：灰度真值每 5s 回源 DB，刷新是 GrayRuleCache 的事")
    void grayUpdateDoesNotTouchBudgetCache() {
        stubSelect(existing("100.00", 1));
        when(mapper.updateById(any(ActivityEntity.class))).thenReturn(1);
        ActivityEntity after = service.updateGray("ACT9001", 5, "70001,70002", 1);
        assertEquals(Integer.valueOf(5), after.getGrayPercent());
        assertEquals("70001,70002", after.getGrayWhitelist());
        verify(budgetService, never()).reheat(anyString(), eq(true));
    }

    @Test
    @DisplayName("灰度越界直接拒（40000）；null 是合法值=清除灰度=全量放行")
    void grayPercentBoundsAndNullMeaning() {
        stubSelect(existing("100.00", 1));
        assertBizCode(40000, () -> service.updateGray("ACT9001", 130, null, 1));
        assertBizCode(40000, () -> service.updateGray("ACT9001", -1, null, 1));
        when(mapper.updateById(any(ActivityEntity.class))).thenReturn(1);
        assertEquals(null, service.updateGray("ACT9001", null, null, 1).getGrayPercent());
    }

    @Test
    @DisplayName("列表把 version 带出去：前端下一次编辑要拿它当乐观锁期望值")
    void listViewCarriesVersion() {
        when(mapper.selectPage(any(IPage.class), any())).thenAnswer(inv -> {
            IPage<ActivityEntity> p = inv.getArgument(0);
            p.setRecords(List.of(existing("100.00", 7)));
            p.setTotal(1);
            return p;
        });
        PageResult<ActivityView> result = service.list(PageQuery.of(1, 20), null);
        assertEquals(1, result.getRecords().size());
        ActivityView view = result.getRecords().get(0);
        assertEquals("ACT9001", view.activityNo());
        assertEquals(Integer.valueOf(7), view.version());
    }

    @Test
    @DisplayName("状态流转的并发冲突也报 41008，不再混在 41000 业务失败里")
    void transitionConflictUses41008() {
        ActivityEntity online = existing("100.00", 2);
        online.setStatus("AUDITING");
        stubSelect(online);
        when(mapper.updateById(any(ActivityEntity.class))).thenReturn(0);
        BizException e = assertThrows(BizException.class,
                () -> service.transition("ACT9001",
                        com.example.marketing.activity.domain.ActivityEvent.APPROVE));
        assertEquals(41008, e.getCode());
    }

    private void assertBizCode(int expected, Runnable action) {
        BizException e = assertThrows(BizException.class, action::run);
        assertEquals(expected, e.getCode());
    }
}
