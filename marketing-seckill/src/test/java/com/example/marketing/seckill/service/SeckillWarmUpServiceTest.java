package com.example.marketing.seckill.service;

import com.example.marketing.common.exception.BizException;
import com.example.marketing.seckill.config.SeckillProperties;
import com.example.marketing.seckill.infrastructure.entity.SeckillActivityEntity;
import com.example.marketing.seckill.infrastructure.mapper.SeckillActivityMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 秒杀分桶重算口径（地雷 A 的秒杀侧）。
 *
 * <p>除了"remain = total - sold 且不为负"，这里还守住一条运维安全线：
 * {@code force=true} 的重预热会把 Redis 桶删掉重建，等于重新开闸 —— 所以**已结束/已下线**
 * 的活动必须被拒绝，否则一次误点就把售罄活动放回可售状态。</p>
 */
class SeckillWarmUpServiceTest {

    private static SeckillActivityEntity activity(String status, int total, Integer sold) {
        SeckillActivityEntity a = new SeckillActivityEntity();
        a.setActivityNo("SK2026001");
        a.setStatus(status);
        a.setTotalStock(total);
        a.setSoldStock(sold);
        a.setBuckets(4);
        return a;
    }

    private static SeckillActivityMapper mapperReturning(SeckillActivityEntity entity) {
        return (SeckillActivityMapper) Proxy.newProxyInstance(
                SeckillActivityMapper.class.getClassLoader(),
                new Class<?>[]{SeckillActivityMapper.class},
                (proxy, method, args) -> "selectOne".equals(method.getName()) ? entity : null);
    }

    private SeckillWarmUpService service(SeckillActivityEntity entity) {
        // stockService 传 null：任何在守卫之前碰 Redis 的实现都会以 NPE 暴露出来
        return new SeckillWarmUpService(mapperReturning(entity), null, new SeckillProperties());
    }

    @Test
    @DisplayName("remain = 总库存 - 已售")
    void remainSubtractsSold() {
        assertEquals(4987, SeckillWarmUpService.remainOf(5000, 13));
    }

    @Test
    @DisplayName("已售为 null（还没开卖）按 0 处理，且不出现负库存")
    void handlesNullSoldAndNeverNegative() {
        assertEquals(5000, SeckillWarmUpService.remainOf(5000, null));
        assertEquals(0, SeckillWarmUpService.remainOf(100, 120));
    }

    @Test
    @DisplayName("注册类型是 seckill-stock")
    void registersAsSeckillStockType() {
        assertEquals("seckill-stock", service(null).type());
    }

    @Test
    @DisplayName("活动不存在就报错，不静默建出一组空桶")
    void unknownActivityFails() {
        assertThrows(BizException.class, () -> service(null).reheat("SK9999999", true));
    }

    @Test
    @DisplayName("已结束的活动拒绝重预热：force 等于重新开闸")
    void refusesToReopenOfflineActivity() {
        SeckillWarmUpService service = service(activity("OFFLINE", 5000, 4990));

        assertThrows(BizException.class, () -> service.reheat("SK2026001", true));
    }
}
