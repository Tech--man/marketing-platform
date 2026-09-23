package com.example.marketing.seckill.service;

import com.example.marketing.common.cache.CacheReheater;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.seckill.config.SeckillRuntimeConfig;
import com.example.marketing.seckill.dto.SeckillActivityCreateRequest;
import com.example.marketing.seckill.dto.SeckillActivityView;
import com.example.marketing.seckill.dto.SeckillStatusRequest;
import com.example.marketing.seckill.dto.StockEditRequest;
import com.example.marketing.seckill.infrastructure.entity.SeckillActivityEntity;
import com.example.marketing.seckill.infrastructure.mapper.SeckillActivityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秒杀活动与库存的后台写路径。
 *
 * <p>两条"不许顺手开闸"的约束是这组端点最要紧的部分：分桶键一旦按新总量重建，
 * 就等于把闸门重新打开——已售进度、停用中的活动、刚建好还没配置完的活动都不该被这样放行。</p>
 */
class SeckillAdminServiceTest {

    private final SeckillActivityMapper mapper = mock(SeckillActivityMapper.class);
    private final SeckillWarmUpService warmUpService = mock(SeckillWarmUpService.class);
    private final SeckillRuntimeConfig runtime = mock(SeckillRuntimeConfig.class);
    private SeckillAdminService service;

    @BeforeEach
    void setUp() {
        service = new SeckillAdminService(mapper, warmUpService, runtime);
        when(warmUpService.reheat(anyString(), anyBoolean()))
                .thenReturn(new CacheReheater.Result("seckill-stock", "SK9009", 0L, 0L, "公式"));
    }

    private SeckillActivityEntity activity(String status, int total, int sold, int version) {
        SeckillActivityEntity a = new SeckillActivityEntity();
        a.setId(88L);
        a.setActivityNo("SK9009");
        a.setItemId(9001L);
        a.setItemName("探针商品");
        a.setSeckillPrice(new BigDecimal("9.90"));
        a.setTotalStock(total);
        a.setSoldStock(sold);
        a.setBuckets(16);
        a.setStatus(status);
        a.setEndTime(LocalDateTime.now().plusDays(1));
        a.setVersion(version);
        return a;
    }

    @Test
    @DisplayName("ONLINE 活动改库存：DB 写完必须 force 重预热分桶")
    void onlineStockEditResetsBuckets() {
        when(mapper.selectOne(any())).thenReturn(activity("ONLINE", 1000, 200, 2));
        when(mapper.updateById(any(SeckillActivityEntity.class))).thenReturn(1);

        service.updateStock("SK9009", new StockEditRequest(1500, 2));

        verify(warmUpService).reheat("SK9009", true);
    }

    @Test
    @DisplayName("OFFLINE 活动改库存：只改 DB，绝不重预热——那等于给停用活动偷偷开闸")
    void offlineStockEditDoesNotReopenGate() {
        when(mapper.selectOne(any())).thenReturn(activity("OFFLINE", 1000, 200, 2));
        when(mapper.updateById(any(SeckillActivityEntity.class))).thenReturn(1);

        service.updateStock("SK9009", new StockEditRequest(1500, 2));

        verify(warmUpService, never()).reheat(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("新总量小于已售直接拒（40000）：remain 会被钳成 0，看着像卖光了")
    void stockBelowSoldRejected() {
        when(mapper.selectOne(any())).thenReturn(activity("ONLINE", 1000, 700, 2));

        BizException e = assertThrows(BizException.class,
                () -> service.updateStock("SK9009", new StockEditRequest(500, 2)));

        assertEquals(40000, e.getCode());
        verify(mapper, never()).updateById(any(SeckillActivityEntity.class));
        verify(warmUpService, never()).reheat(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("expectedVersion 过期 → 41008，不写库也不动桶")
    void staleVersionRejected() {
        when(mapper.selectOne(any())).thenReturn(activity("ONLINE", 1000, 200, 9));

        BizException e = assertThrows(BizException.class,
                () -> service.updateStock("SK9009", new StockEditRequest(1500, 2)));

        assertEquals(41008, e.getCode());
        verify(mapper, never()).updateById(any(SeckillActivityEntity.class));
    }

    @Test
    @DisplayName("updateById 返回 0（真并发）同样是 41008 且不重预热")
    void concurrentUpdateIsConflict() {
        when(mapper.selectOne(any())).thenReturn(activity("ONLINE", 1000, 200, 2));
        when(mapper.updateById(any(SeckillActivityEntity.class))).thenReturn(0);

        assertThrows(BizException.class, () -> service.updateStock("SK9009", new StockEditRequest(1500, 2)));
        verify(warmUpService, never()).reheat(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("新建活动落成 OFFLINE 且不预热：配好之前不许开闸")
    void createIsOfflineAndNeverWarms() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(runtime.buckets()).thenReturn(16);

        SeckillActivityEntity created = service.create(new SeckillActivityCreateRequest(
                "SK9100", 9002L, "新秒杀", new BigDecimal("19.90"), 500,
                LocalDateTime.now(), LocalDateTime.now().plusDays(1)));

        assertEquals("OFFLINE", created.getStatus());
        assertEquals(Integer.valueOf(16), created.getBuckets());
        verify(mapper).insert(any(SeckillActivityEntity.class));
        verify(warmUpService, never()).reheat(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("编号重复是 41000 业务冲突，不是 41008")
    void duplicateActivityNoIsBizError() {
        when(mapper.selectOne(any())).thenReturn(activity("OFFLINE", 1000, 0, 0));
        when(runtime.buckets()).thenReturn(16);

        BizException e = assertThrows(BizException.class, () -> service.create(
                new SeckillActivityCreateRequest("SK9009", 9002L, "重号", new BigDecimal("19.90"),
                        500, LocalDateTime.now(), LocalDateTime.now().plusDays(1))));

        assertEquals(41000, e.getCode());
        verify(mapper, never()).insert(any(SeckillActivityEntity.class));
    }

    @Test
    @DisplayName("上线：DB 改完用 force=false 补建缺失分桶（已有进度不许被冲掉）")
    void goingOnlineFillsMissingBuckets() {
        when(mapper.selectOne(any())).thenReturn(activity("OFFLINE", 1000, 0, 3));
        when(mapper.updateById(any(SeckillActivityEntity.class))).thenReturn(1);

        SeckillActivityEntity after = service.updateStatus("SK9009", new SeckillStatusRequest("ONLINE", 3));

        assertEquals("ONLINE", after.getStatus());
        verify(warmUpService).reheat("SK9009", false);
    }

    @Test
    @DisplayName("下线：只改状态，不碰桶（在途的抢购该让它跑完）")
    void goingOfflineLeavesBuckets() {
        when(mapper.selectOne(any())).thenReturn(activity("ONLINE", 1000, 300, 4));
        when(mapper.updateById(any(SeckillActivityEntity.class))).thenReturn(1);

        service.updateStatus("SK9009", new SeckillStatusRequest("OFFLINE", 4));

        verify(warmUpService, never()).reheat(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("status 只接受 ONLINE/OFFLINE")
    void unknownStatusRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service.updateStatus("SK9009", new SeckillStatusRequest("PAUSED", 1)));
        assertEquals(40000, e.getCode());
    }

    @Test
    @DisplayName("视图带 version：编辑时回传做乐观锁")
    void viewCarriesVersion() {
        SeckillActivityView view = SeckillActivityView.from(activity("ONLINE", 1000, 200, 7));
        assertEquals(Integer.valueOf(7), view.version());
        assertEquals("SK9009", view.activityNo());
    }
}
