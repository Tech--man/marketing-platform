package com.example.marketing.seckill.consumer;

import com.example.marketing.common.message.LocalMessageService;
import com.example.marketing.common.mq.SeckillOrderEvent;
import com.example.marketing.common.util.JsonUtils;
import com.example.marketing.seckill.infrastructure.entity.SeckillActivityEntity;
import com.example.marketing.seckill.infrastructure.entity.SeckillOrderEntity;
import com.example.marketing.seckill.infrastructure.mapper.SeckillActivityMapper;
import com.example.marketing.seckill.infrastructure.mapper.SeckillOrderMapper;
import com.example.marketing.seckill.service.SeckillStockService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

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
 * H7 回归（2026-09-29 架构审查）：消费端幂等回放分支的防御——
 * 撞唯一键后查到的既有订单若是 <b>CANCELLED</b>，绝不能把它的单号当 SUCCESS 回放
 * （用户会拿着已取消的单去支付被拒），而是回补本次重扣的名额并写 FAIL。
 *
 * <p>新索引（带 active 列）下正常流程不会再走到这条分支；留它是防 schema 被回退。</p>
 */
class SeckillOrderConsumerTest {

    private final SeckillOrderMapper orderMapper = mock(SeckillOrderMapper.class);
    private final SeckillActivityMapper activityMapper = mock(SeckillActivityMapper.class);
    private final SeckillStockService stockService = mock(SeckillStockService.class);
    private final LocalMessageService localMessageService = mock(LocalMessageService.class);
    private final SeckillOrderConsumer consumer = new SeckillOrderConsumer(
            orderMapper, activityMapper, stockService, localMessageService,
            new SimpleMeterRegistry());

    private SeckillOrderEvent event() {
        SeckillOrderEvent event = new SeckillOrderEvent();
        event.setActivityNo("SK1");
        event.setUserId(70001L);
        event.setItemId(1L);
        event.setToken("tk-regrab");
        event.setBucket(3);
        return event;
    }

    private void activityOnline() {
        SeckillActivityEntity activity = new SeckillActivityEntity();
        activity.setActivityNo("SK1");
        activity.setBuckets(16);
        when(activityMapper.selectOne(any())).thenReturn(activity);
    }

    @Test
    @DisplayName("回放撞上 CANCELLED 订单 → 回补名额 + FAIL，绝不回放已取消单号")
    void cancelledReplayWritesFailAndRefills() {
        activityOnline();
        when(orderMapper.insert(any(SeckillOrderEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_activity_user"));
        SeckillOrderEntity cancelled = new SeckillOrderEntity();
        cancelled.setOrderNo("SK-OLD");
        cancelled.setStatus("CANCELLED");
        when(orderMapper.selectOne(any())).thenReturn(cancelled);

        consumer.handle(JsonUtils.toJson(event()));

        verify(stockService).refill(eq("SK1"), eq(70001L), eq(3), eq(16));
        verify(stockService).saveResult(eq("tk-regrab"), eq("FAIL:ORDER_CANCELLED_REGRAB"));
    }

    @Test
    @DisplayName("回放撞上 CREATED/PAID 订单 → 照旧 SUCCESS 回放（真重复投递的幂等语义不变）")
    void liveOrderReplayStillSuccess() {
        activityOnline();
        when(orderMapper.insert(any(SeckillOrderEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_activity_user"));
        SeckillOrderEntity created = new SeckillOrderEntity();
        created.setOrderNo("SK-LIVE");
        created.setStatus("CREATED");
        when(orderMapper.selectOne(any())).thenReturn(created);

        consumer.handle(JsonUtils.toJson(event()));

        verify(stockService).saveResult("tk-regrab", "SUCCESS:SK-LIVE");
        verify(stockService, never()).refill(anyString(), anyLong(), anyInt(), anyInt());
    }
}
