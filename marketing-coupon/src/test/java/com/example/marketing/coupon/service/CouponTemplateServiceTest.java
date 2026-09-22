package com.example.marketing.coupon.service;

import com.example.marketing.common.exception.BizException;
import com.example.marketing.coupon.infrastructure.entity.CouponTemplateEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.example.marketing.coupon.service.CouponTestSupport.templateMapperReturning;
import static com.example.marketing.coupon.service.CouponTestSupport.userCouponMapperReturning;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 券库存重算口径（地雷 A）：remain 必须由 total_stock 与已发放数算出来，
 * 不能拿 Redis 里那个旧值 —— 运营抬完 total_stock 之后要能真的生效。
 */
class CouponTemplateServiceTest {

    private CouponTemplateEntity template(int totalStock) {
        CouponTemplateEntity t = new CouponTemplateEntity();
        t.setId(1L);
        t.setTemplateNo("CT2026001");
        t.setTotalStock(totalStock);
        t.setStatus(CouponTemplateService.STATUS_ACTIVE);
        return t;
    }

    @Test
    @DisplayName("剩余 = 总库存 - 已发放")
    void remainSubtractsIssued() {
        assertEquals(70L, CouponTemplateService.remainOf(template(100), 30L));
    }

    @Test
    @DisplayName("已发超时归零，不能算出负库存写进 Redis")
    void neverNegative() {
        assertEquals(0L, CouponTemplateService.remainOf(template(100), 120L));
    }

    @Test
    @DisplayName("注册类型是 coupon-stock，注册表按它分发")
    void registersAsCouponStockType() {
        assertEquals("coupon-stock", new CouponTemplateService(null, null, null).type());
    }

    @Test
    @DisplayName("模板不存在就报错，不静默按 0 预热")
    void unknownTemplateFails() {
        CouponTemplateService service = new CouponTemplateService(
                templateMapperReturning(null), userCouponMapperReturning(0L), null);

        assertThrows(BizException.class, () -> service.reheat("CT0000000", true));
    }

    @Test
    @DisplayName("重算只读 DB：Redis 桩传 null 也不该被碰")
    void recomputeNeverReadsRedis() {
        CouponTemplateService service = new CouponTemplateService(
                templateMapperReturning(template(100)), userCouponMapperReturning(40L), null);

        assertEquals(60L, service.computeRemainByNo("CT2026001"));
    }
}
