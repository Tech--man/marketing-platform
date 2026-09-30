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

import java.util.List;

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
 * <p>P0/P1 回归（2026-09-30 第二轮复审）：取消占位改 NULL 后，回放查询必须带
 * {@code active=1} 过滤——「一张取消单 + 一张有效单」是新 schema 的常态，不过滤的
 * selectOne 会抛 TooManyResults、FAIL 覆盖先前 SUCCESS。同 token 才是真重复投递
 * （SUCCESS 回放）；token 不同是 bought 标记过期后的重抢（用户已持有活单），
 * 必须回补本次名额并写 FAIL，不能回放旧单号装作成功。</p>
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
    @DisplayName("回放撞上 CANCELLED 订单（旧索引形状）→ 回补名额 + FAIL，绝不回放已取消单号")
    void cancelledReplayWritesFailAndRefills() {
        activityOnline();
        when(orderMapper.insert(any(SeckillOrderEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_activity_user"));
        // 新索引形状下有效单口径查不到（取消单是 active=NULL），全量行里才有取消单
        when(orderMapper.selectOne(any())).thenReturn(null);
        SeckillOrderEntity cancelled = new SeckillOrderEntity();
        cancelled.setOrderNo("SK-OLD");
        cancelled.setStatus("CANCELLED");
        when(orderMapper.selectList(any())).thenReturn(List.of(cancelled));

        consumer.handle(JsonUtils.toJson(event()));

        verify(stockService).refill(eq("SK1"), eq(70001L), eq(3), eq(16));
        verify(stockService).saveResult(eq("tk-regrab"), eq("FAIL:ORDER_CANCELLED_REGRAB"));
    }

    @Test
    @DisplayName("回放撞上同 token 的活单（真重复投递）→ 照旧 SUCCESS 回放")
    void liveOrderReplayStillSuccess() {
        activityOnline();
        when(orderMapper.insert(any(SeckillOrderEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_activity_user"));
        SeckillOrderEntity created = new SeckillOrderEntity();
        created.setOrderNo("SK-LIVE");
        created.setStatus("CREATED");
        created.setToken("tk-regrab"); // 同一条消息重投：token 与库内单一致
        when(orderMapper.selectOne(any())).thenReturn(created);

        consumer.handle(JsonUtils.toJson(event()));

        verify(stockService).saveResult("tk-regrab", "SUCCESS:SK-LIVE");
        verify(stockService, never()).refill(anyString(), anyLong(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("bought 标记过期后重抢已持有活单（token 不同）→ 回补本次名额 + FAIL，不回放旧单号")
    void activeOrderRegrabWritesFailAndRefills() {
        activityOnline();
        when(orderMapper.insert(any(SeckillOrderEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_activity_user"));
        SeckillOrderEntity paid = new SeckillOrderEntity();
        paid.setOrderNo("SK-PAID");
        paid.setStatus("PAID");
        paid.setToken("tk-first-grab"); // 第一轮抢购的 token，与本次消息不同
        when(orderMapper.selectOne(any())).thenReturn(paid);

        consumer.handle(JsonUtils.toJson(event()));

        verify(stockService).refill(eq("SK1"), eq(70001L), eq(3), eq(16));
        verify(stockService).saveResult(eq("tk-regrab"), eq("FAIL:ACTIVE_ORDER_EXISTS"));
        verify(stockService, never()).saveResult(anyString(), org.mockito.ArgumentMatchers.startsWith("SUCCESS"));
    }
}
