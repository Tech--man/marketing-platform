package com.example.marketing.coupon.service;

import com.example.marketing.coupon.infrastructure.entity.CouponTemplateEntity;
import com.example.marketing.coupon.infrastructure.mapper.CouponTemplateMapper;
import com.example.marketing.coupon.infrastructure.mapper.UserCouponMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 券库存自检：Redis 余量 vs {@code total_stock - 已发数}。
 * 口径复用 {@code remainOf} 那一份，不在这里重写第二遍。
 */
class CouponConsistencyTest {

    private CouponTemplateMapper templateMapper;
    private UserCouponMapper userCouponMapper;
    private CouponStockService stockService;
    private CouponTemplateService service;

    @BeforeEach
    void setUp() {
        templateMapper = mock(CouponTemplateMapper.class);
        userCouponMapper = mock(UserCouponMapper.class);
        stockService = mock(CouponStockService.class);
        service = new CouponTemplateService(templateMapper, userCouponMapper, stockService);

        CouponTemplateEntity template = new CouponTemplateEntity();
        template.setId(7L);
        template.setTotalStock(100);
        when(templateMapper.selectList(any())).thenReturn(List.of(template));
        when(userCouponMapper.selectCount(any())).thenReturn(40L);   // 期望余量 60
    }

    @Test
    @DisplayName("余量对得上 → 0")
    void match() {
        when(stockService.remainStock(7L)).thenReturn(60L);

        assertEquals(0, service.mismatchCount());
    }

    @Test
    @DisplayName("改了 total_stock 没重预热 → 报出来（地雷 A 的形状）")
    void staleAfterStockEdit() {
        when(stockService.remainStock(7L)).thenReturn(100L);

        assertEquals(1, service.mismatchCount());
    }

    @Test
    @DisplayName("判定不了是 -1：不能因为 Redis 挂了就说一切正常")
    void unknownStaysUnknown() {
        when(stockService.remainStock(7L)).thenThrow(new IllegalStateException("redis down"));

        assertEquals(-1, service.mismatchCount());
    }
}
