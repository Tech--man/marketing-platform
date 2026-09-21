package com.example.marketing.seckill.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.marketing.common.message.LocalMessageService;
import com.example.marketing.common.mq.MqTopics;
import com.example.marketing.common.mq.SeckillOrderEvent;
import com.example.marketing.common.mq.StreamMessageHandler;
import com.example.marketing.common.util.JsonUtils;
import com.example.marketing.seckill.domain.SeckillOrderStatus;
import com.example.marketing.seckill.infrastructure.entity.SeckillActivityEntity;
import com.example.marketing.seckill.infrastructure.entity.SeckillOrderEntity;
import com.example.marketing.seckill.infrastructure.mapper.SeckillActivityMapper;
import com.example.marketing.seckill.infrastructure.mapper.SeckillOrderMapper;
import com.example.marketing.seckill.service.SeckillStockService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 秒杀异步下单消费者。
 *
 * <p>幂等三层：token 幂等键 + seckill_order unique(activity_no,user_id) +
 * DuplicateKeyException 捕获后回放已成功订单。下单成功后活动已售数 +1
 * （SQL 原子自增，不加行锁）。失败写 FAIL 结果并抛异常交给重试
 * （Full 形态 RocketMQ 指数退避 / Lite 形态 Stream 容器内 3 次 + 本地消息表补偿）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.TOPIC_SECKILL_ORDER,
        selectorExpression = MqTopics.TAG_ORDER,
        consumerGroup = MqTopics.GROUP_SECKILL,
        consumeThreadNumber = 8)
public class SeckillOrderConsumer implements RocketMQListener<String>, StreamMessageHandler {

    private static final DateTimeFormatter ORDER_TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SeckillOrderMapper orderMapper;
    private final SeckillActivityMapper activityMapper;
    private final SeckillStockService stockService;
    private final LocalMessageService localMessageService;
    private final MeterRegistry meterRegistry;

    @Override
    public String topic() {
        return MqTopics.TOPIC_SECKILL_ORDER;
    }

    @Override
    public String group() {
        return MqTopics.GROUP_SECKILL;
    }

    @Override
    public void handle(String payload) {
        SeckillOrderEvent event = JsonUtils.parse(payload, SeckillOrderEvent.class);
        try {
            persistOrder(event);
            localMessageService.confirm("seckill:" + event.getToken());
        } catch (Exception e) {
            // 下单失败：写 FAIL 结果（用户轮询可见），抛异常交给重试；
            // 重试仍失败后由本地消息表补偿重发，库存由超时回补 Job 兜底归还
            stockService.saveResult(event.getToken(), "FAIL:" + brief(e));
            throw e;
        }
    }

    @Override
    public void onMessage(String payload) {
        handle(payload);
    }

    private void persistOrder(SeckillOrderEvent event) {
        SeckillActivityEntity activity = activityMapper.selectOne(
                new LambdaQueryWrapper<SeckillActivityEntity>()
                        .eq(SeckillActivityEntity::getActivityNo, event.getActivityNo()));
        if (activity == null) {
            stockService.saveResult(event.getToken(), "FAIL:ACTIVITY_NOT_FOUND");
            return;
        }
        SeckillOrderEntity order = new SeckillOrderEntity();
        order.setOrderNo(generateOrderNo());
        order.setActivityNo(event.getActivityNo());
        order.setUserId(event.getUserId());
        order.setItemId(event.getItemId());
        order.setAmount(activity.getSeckillPrice());
        order.setStatus(SeckillOrderStatus.CREATED.name());
        order.setToken(event.getToken());
        order.setBucket(event.getBucket());
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 重复购买：幂等回放已有订单
            SeckillOrderEntity existing = orderMapper.selectOne(
                    new LambdaQueryWrapper<SeckillOrderEntity>()
                            .eq(SeckillOrderEntity::getActivityNo, event.getActivityNo())
                            .eq(SeckillOrderEntity::getUserId, event.getUserId()));
            stockService.saveResult(event.getToken(), "SUCCESS:" + existing.getOrderNo());
            log.info("[seckill-consumer] 重复下单幂等忽略 token={}, orderNo={}", event.getToken(), existing.getOrderNo());
            return;
        }
        // 已售数原子 +1（SQL 自增避免乐观锁冲突风暴）
        activityMapper.update(null, new UpdateWrapper<SeckillActivityEntity>()
                .eq("activity_no", event.getActivityNo())
                .setSql("sold_stock = sold_stock + 1"));
        stockService.saveResult(event.getToken(), "SUCCESS:" + order.getOrderNo());
        Counter.builder("seckill.order.persisted").register(meterRegistry).increment();
        log.info("[seckill-consumer] 下单成功 token={}, orderNo={}", event.getToken(), order.getOrderNo());
    }

    private String generateOrderNo() {
        return "SK" + LocalDateTime.now().format(ORDER_TIME_FMT)
                + ThreadLocalRandom.current().nextInt(100000, 999999);
    }

    private String brief(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return msg.length() > 60 ? msg.substring(0, 60) : msg;
    }
}
