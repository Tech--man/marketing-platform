package com.example.marketing.seckill.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.message.LocalMessageService;
import com.example.marketing.common.mq.MqTopics;
import com.example.marketing.common.idempotent.BizKey;
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
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * 建单、已售数递增、消息确认合成一个事务：原本三条语句各自 autocommit，每条消息要等
     * 三次 InnoDB redo fsync，这是 LITE 消费端吞吐的数量级瓶颈；顺带也让"有单必有库存递增"
     * 从"最终一致"变成真原子。
     *
     * <p>{@code onMessage} 同样必须标注：内部自调用绕过代理，只标 handle 会让 Full 形态
     * 静默没有事务。</p>
     */
    @Transactional
    @Override
    public void handle(String payload) {
        SeckillOrderEvent event = JsonUtils.parse(payload, SeckillOrderEvent.class);
        try {
            persistOrder(event);
            localMessageService.confirm(MqTopics.TOPIC_SECKILL_ORDER, BizKey.of("seckill", event.getToken()));
        } catch (Exception e) {
            // 撞在并发重复投递上的"在途重复"不能写 FAIL：那一单其实正在被另一个线程成功落下，
            // 写 FAIL 会把已经发生的成功覆盖成失败（用户看到失败、库存却已扣）。
            // 只抛回去交给重投，重投时对方已提交，走幂等回放拿到同一个 orderNo。
            if (!isInFlightDuplicate(e)) {
                // 下单失败：写 FAIL 结果（用户轮询可见），抛异常交给重试（事务随之回滚，
                // 本条结果以重投后的写入为准）；
                // 重试仍失败后由本地消息表补偿重发，库存由超时回补 Job 兜底归还
                stockService.saveResult(event.getToken(), "FAIL:" + brief(e));
            }
            throw e;
        }
    }

    @Transactional
    @Override
    public void onMessage(String payload) {
        handle(payload);
    }

    /** 只有 persistOrder 里那处显式抛的 DUPLICATE_REQUEST 算"在途重复" */
    private static boolean isInFlightDuplicate(Exception e) {
        return e instanceof BizException b && b.getCode() == ErrorCode.DUPLICATE_REQUEST.getCode();
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
            if (existing == null) {
                // 唯一索引撞上了、本事务的快照里却查不到 = 撞上一个尚未提交的并发插入
                // （同一消息被重复投递时会出现）。此刻既不能当成功也不能当失败，抛回去重投。
                Counter.builder("seckill.order.inflight_duplicate").register(meterRegistry).increment();
                log.warn("[seckill-consumer] 撞上在途重复下单，交给重投 activityNo={}, userId={}, token={}",
                        event.getActivityNo(), event.getUserId(), event.getToken());
                throw BizException.of(ErrorCode.DUPLICATE_REQUEST);
            }
            if (SeckillOrderStatus.CANCELLED.name().equals(existing.getStatus())) {
                // H7 防御分支：唯一索引已改为只约束有效单（带 active），正常流程不该走到这里——
                // 留这条防御是防 schema 被回退成旧索引。那时把已取消单号当 SUCCESS 回放，
                // 用户会拿着一个永远付不了款的单号，且本次重扣的名额无主：回补名额、写 FAIL。
                Counter.builder("seckill.order.cancelled_replay_guard").register(meterRegistry).increment();
                log.warn("[seckill-consumer] 幂等回放撞上已取消订单，回补本次名额并写 FAIL "
                        + "activityNo={}, userId={}, oldOrder={}", event.getActivityNo(),
                        event.getUserId(), existing.getOrderNo());
                stockService.refill(event.getActivityNo(), event.getUserId(),
                        event.getBucket() == null ? 1 : event.getBucket(), activity.getBuckets());
                stockService.saveResult(event.getToken(), "FAIL:ORDER_CANCELLED_REGRAB");
                return;
            }
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
