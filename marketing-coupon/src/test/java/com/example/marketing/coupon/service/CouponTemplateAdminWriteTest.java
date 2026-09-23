package com.example.marketing.coupon.service;

import com.example.marketing.common.cache.CacheReheater;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.coupon.dto.StatusUpdateRequest;
import com.example.marketing.coupon.dto.StockUpdateRequest;
import com.example.marketing.coupon.dto.TemplateCreateRequest;
import com.example.marketing.coupon.infrastructure.entity.CouponTemplateEntity;
import com.example.marketing.coupon.infrastructure.mapper.CouponTemplateMapper;
import com.example.marketing.coupon.infrastructure.mapper.UserCouponMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 券模板写路径的四条硬约束。
 *
 * <p>与预算同族的地雷 A：Redis 键里存的是"剩余量"，{@code warmStock} 是 SETNX，
 * 所以改了 {@code total_stock} 不 DEL 重建就永远不会生效。这里断言的是
 * "走的是 overwrite（force）那条路"，而不是"看起来调用过缓存相关方法"。</p>
 */
class CouponTemplateAdminWriteTest {

    private final CouponTemplateMapper templateMapper = mock(CouponTemplateMapper.class);
    private final UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
    private final CouponStockService stockService = mock(CouponStockService.class);
    private final CouponTemplateService service =
            new CouponTemplateService(templateMapper, userCouponMapper, stockService);

    private CouponTemplateEntity template(int totalStock, int version) {
        CouponTemplateEntity t = new CouponTemplateEntity();
        t.setId(77L);
        t.setTemplateNo("CT9009");
        t.setActivityNo("ACT2026001");
        t.setName("探针模板");
        t.setCouponType("FULL_REDUCTION");
        t.setFaceValue(new BigDecimal("20.00"));
        t.setThresholdAmount(new BigDecimal("100.00"));
        t.setTotalStock(totalStock);
        t.setPerUserLimit(1);
        t.setValidDays(7);
        t.setStatus(CouponTemplateService.STATUS_ACTIVE);
        t.setVersion(version);
        return t;
    }

    private TemplateCreateRequest createBody(String no) {
        return new TemplateCreateRequest(no, "ACT2026001", "探针模板", "FULL_REDUCTION",
                new BigDecimal("20.00"), new BigDecimal("100.00"), 500, 1, 7,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
    }

    @Test
    @DisplayName("新建必须显式预热库存键：不 warm 的话第一笔领券吃 NOT_WARMED")
    void createWarmsStock() {
        when(templateMapper.selectOne(any())).thenReturn(null);

        service.create(createBody("CT9100"));

        verify(templateMapper).insert(any(CouponTemplateEntity.class));
        verify(stockService).warmIfAbsent(any(CouponTemplateEntity.class), anyLong());
        verify(stockService, never()).overwrite(any(), anyLong());
    }

    @Test
    @DisplayName("templateNo 重复是 41000 业务冲突，不是 41008（41008 只表示并发覆盖）")
    void duplicateTemplateNoIsBizError() {
        when(templateMapper.selectOne(any())).thenReturn(template(500, 0));

        BizException e = assertThrows(BizException.class, () -> service.create(createBody("CT9009")));

        assertEquals(41000, e.getCode());
        verify(templateMapper, never()).insert(any(CouponTemplateEntity.class));
    }

    @Test
    @DisplayName("改总库存必须 force 重建（overwrite），且新余量=新总量-已发数")
    void stockUpdateForcesRebuild() {
        when(templateMapper.selectOne(any())).thenReturn(template(1000, 2));
        when(userCouponMapper.selectCount(any())).thenReturn(40L);
        when(templateMapper.updateById(any(CouponTemplateEntity.class))).thenReturn(1);
        when(stockService.remainStock(anyLong())).thenReturn(960L, 920L);

        service.updateTotalStock("CT9009", new StockUpdateRequest(960, 2));

        // 1000→960 总量、已发 40 ⇒ 重建后剩余 920
        verify(stockService).overwrite(any(CouponTemplateEntity.class), eq(920L));
        verify(stockService, never()).warmIfAbsent(any(), anyLong());
    }

    @Test
    @DisplayName("把总库存改到低于已发数直接拒（40000）：否则余量被钳成 0，看着像卖光了")
    void stockBelowIssuedRejected() {
        when(templateMapper.selectOne(any())).thenReturn(template(1000, 2));
        when(userCouponMapper.selectCount(any())).thenReturn(400L);

        BizException e = assertThrows(BizException.class,
                () -> service.updateTotalStock("CT9009", new StockUpdateRequest(300, 2)));

        assertEquals(40000, e.getCode());
        verify(templateMapper, never()).updateById(any(CouponTemplateEntity.class));
        verify(stockService, never()).overwrite(any(), anyLong());
    }

    @Test
    @DisplayName("expectedVersion 过期 → 41008：既不写库也不动键")
    void staleVersionRejected() {
        when(templateMapper.selectOne(any())).thenReturn(template(1000, 5));

        BizException e = assertThrows(BizException.class,
                () -> service.updateTotalStock("CT9009", new StockUpdateRequest(960, 2)));

        assertEquals(41008, e.getCode());
        verify(templateMapper, never()).updateById(any(CouponTemplateEntity.class));
        verify(stockService, never()).overwrite(any(), anyLong());
    }

    @Test
    @DisplayName("上线（INACTIVE→ACTIVE）只补热不重建：键可能从没建过，但不能覆盖已在的正确余量")
    void statusChangeOnlyFillsMissingKey() {
        when(templateMapper.selectOne(any())).thenReturn(template(1000, 3));
        when(userCouponMapper.selectCount(any())).thenReturn(40L);
        when(templateMapper.updateById(any(CouponTemplateEntity.class))).thenReturn(1);
        when(stockService.remainStock(anyLong())).thenReturn(960L);

        CouponTemplateEntity after = service.updateStatus("CT9009", new StatusUpdateRequest("ACTIVE", 3));

        assertEquals("ACTIVE", after.getStatus());
        verify(stockService).warmIfAbsent(any(CouponTemplateEntity.class), eq(960L));
        verify(stockService, never()).overwrite(any(), anyLong());
    }

    @Test
    @DisplayName("status 只接受 ACTIVE/INACTIVE，别的写法直接 40000")
    void unknownStatusRejected() {
        when(templateMapper.selectOne(any())).thenReturn(template(1000, 3));

        BizException e = assertThrows(BizException.class,
                () -> service.updateStatus("CT9009", new StatusUpdateRequest("PAUSED", 3)));
        assertEquals(40000, e.getCode());
    }

    @Test
    @DisplayName("reheat 的公式只有一份：改库存走的就是它，不另算一遍")
    void stockUpdateGoesThroughTheSingleReheatFormula() {
        when(templateMapper.selectOne(any())).thenReturn(template(1000, 2));
        when(userCouponMapper.selectCount(any())).thenReturn(0L);
        when(templateMapper.updateById(any(CouponTemplateEntity.class))).thenReturn(1);
        when(stockService.remainStock(anyLong())).thenReturn(1000L, 960L);

        service.updateTotalStock("CT9009", new StockUpdateRequest(960, 2));

        verify(stockService).overwrite(any(CouponTemplateEntity.class), eq(960L));
        CacheReheater.Result r = service.reheat("CT9009", true);
        assertEquals("coupon-stock", r.type());
    }
}
