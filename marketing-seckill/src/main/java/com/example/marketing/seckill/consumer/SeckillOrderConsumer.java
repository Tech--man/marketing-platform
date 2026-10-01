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
     * N-7：LITE 通道的 tag 声明——与 Full 形态 selectorExpression 同源常量。
     * 本 topic 将来出现第二个 tag 时，Stream 侧拒投并计 stream.consumer.tag_rejected，
     * 而不是把别的事件按 SeckillOrderEvent 解析（错账方向不可控）。
     */
    @Override
    public boolean acceptsTag(String tag) {
        return MqTopics.TAG_ORDER.equals(tag);
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
        // W2.6（2026-09-30 第二轮复审）：orderNo = 秒级时间戳 + 6 位随机，同秒高 TPS 下
        // 期望碰撞不可忽略（1000 TPS 时约 0.5 次/秒）——撞 uk_order_no 而非防重索引时，
        // 此前会被"在途重复"分支误读成并发插入而外抛重投（自愈但放大重试）。先按
        // orderNo 回查区分：撞单号就换号重试（上限 2 次），再撞才交重投。
        for (int attempt = 1; ; attempt++) {
            SeckillOrderEntity order = buildOrder(event, activity);
            try {
                orderMapper.insert(order);
                onOrderPersisted(event, order, activity);
                return;
            } catch (DuplicateKeyException e) {
                if (attempt <= 2 && orderNoTaken(order.getOrderNo())) {
                    Counter.builder("seckill.order.orderno_collision").register(meterRegistry).increment();
                    log.warn("[seckill-consumer] 订单号随机撞码，换号重试 orderNo={}, attempt={}",
                            order.getOrderNo(), attempt);
                    continue;
                }
                handleDuplicatePurchase(event, order, activity);
                return;
            }
        }
    }

    private SeckillOrderEntity buildOrder(SeckillOrderEvent event, SeckillActivityEntity activity) {
        SeckillOrderEntity order = new SeckillOrderEntity();
        order.setOrderNo(generateOrderNo());
        order.setActivityNo(event.getActivityNo());
        order.setUserId(event.getUserId());
        order.setItemId(event.getItemId());
        order.setAmount(activity.getSeckillPrice());
        order.setStatus(SeckillOrderStatus.CREATED.name());
        order.setToken(event.getToken());
        order.setBucket(event.getBucket());
        return order;
    }

    private void onOrderPersisted(SeckillOrderEvent event, SeckillOrderEntity order,
                                  SeckillActivityEntity activity) {
        // 已售数原子 +1（SQL 自增避免乐观锁冲突风暴）
        activityMapper.update(null, new UpdateWrapper<SeckillActivityEntity>()
                .eq("activity_no", event.getActivityNo())
                .setSql("sold_stock = sold_stock + 1"));
        stockService.saveResult(event.getToken(), "SUCCESS:" + order.getOrderNo());
        Counter.builder("seckill.order.persisted").register(meterRegistry).increment();
        log.info("[seckill-consumer] 下单成功 token={}, orderNo={}", event.getToken(), order.getOrderNo());
    }

    private boolean orderNoTaken(String orderNo) {
        Long count = orderMapper.selectCount(new LambdaQueryWrapper<SeckillOrderEntity>()
                .eq(SeckillOrderEntity::getOrderNo, orderNo));
        return count != null && count > 0;
    }

    /**
     * 撞防重唯一键（uk_activity_user）的处置：幂等回放 / 取消防御 / 在途重复 / bought
     * 过期重抢四形态。撞 uk_order_no 的情形已在 persistOrder 外层消化，不会进到这里。
     */
    private void handleDuplicatePurchase(SeckillOrderEvent event, SeckillOrderEntity order,
                                         SeckillActivityEntity activity) {
        // 撞唯一键：先按「有效单」口径查（新索引形状下撞的必是 active=1 的行）。
        // 不带 active 过滤会命中「一张取消单 + 一张有效单」两行 → selectOne 抛
        // TooManyResults → 外层 catch 写 FAIL 覆盖先前 SUCCESS（2026-09-30 复审 P1）。
        SeckillOrderEntity existing = orderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrderEntity>()
                        .eq(SeckillOrderEntity::getActivityNo, event.getActivityNo())
                        .eq(SeckillOrderEntity::getUserId, event.getUserId())
                        .eq(SeckillOrderEntity::getActive, 1));
        if (existing == null) {
            // 撞了键却查不到有效单，两种形状：撞上未提交的并发插入（在途重复，
            // 抛回去重投），或 schema 被回退成旧两列索引、撞上的是取消单（走
            // 下面的取消防御分支）。用全量行区分。
            SeckillOrderEntity cancelled = orderMapper.selectList(
                            new LambdaQueryWrapper<SeckillOrderEntity>()
                                    .eq(SeckillOrderEntity::getActivityNo, event.getActivityNo())
                                    .eq(SeckillOrderEntity::getUserId, event.getUserId()))
                    .stream()
                    .filter(r -> SeckillOrderStatus.CANCELLED.name().equals(r.getStatus()))
                    .findFirst().orElse(null);
            if (cancelled != null) {
                // H7 防御分支：正常流程不该走到这里——留它是防 schema 被回退成
                // 旧索引。那时把已取消单号当 SUCCESS 回放，用户会拿着一个永远
                // 付不了款的单号，且本次重扣的名额无主：回补名额、写 FAIL。
                Counter.builder("seckill.order.cancelled_replay_guard").register(meterRegistry).increment();
                log.warn("[seckill-consumer] 幂等回放撞上已取消订单（旧索引形状？），回补本次名额并写 FAIL "
                        + "activityNo={}, userId={}, oldOrder={}", event.getActivityNo(),
                        event.getUserId(), cancelled.getOrderNo());
                stockService.refill(event.getActivityNo(), event.getUserId(),
                        event.getBucket() == null ? 1 : event.getBucket(), activity.getBuckets());
                stockService.saveResult(event.getToken(), "FAIL:ORDER_CANCELLED_REGRAB");
                return;
            }
            Counter.builder("seckill.order.inflight_duplicate").register(meterRegistry).increment();
            log.warn("[seckill-consumer] 撞上在途重复下单，交给重投 activityNo={}, userId={}, token={}",
                    event.getActivityNo(), event.getUserId(), event.getToken());
            throw BizException.of(ErrorCode.DUPLICATE_REQUEST);
        }
        if (existing.getToken() != null && existing.getToken().equals(event.getToken())) {
            // 真重复投递（同一条消息）：幂等回放同一张单
            stockService.saveResult(event.getToken(), "SUCCESS:" + existing.getOrderNo());
            log.info("[seckill-consumer] 重复下单幂等忽略 token={}, orderNo={}",
                    event.getToken(), existing.getOrderNo());
            return;
        }
        // token 不同 = bought 标记过期后的重抢（用户已持有活单，TTL 错配见
        // SeckillStockService）。本次 Lua 又扣了一个名额且无主——回补、写 FAIL，
        // 不能回放旧单号装作成功（2026-09-30 复审 P1：此前每用户每天净烧 1 名额）。
        Counter.builder("seckill.order.active_order_regrab_guard").register(meterRegistry).increment();
        log.warn("[seckill-consumer] bought 标记过期后重抢已持有活单，回补本次名额并写 FAIL "
                        + "activityNo={}, userId={}, liveOrder={}", event.getActivityNo(),
                event.getUserId(), existing.getOrderNo());
        stockService.refill(event.getActivityNo(), event.getUserId(),
                event.getBucket() == null ? 1 : event.getBucket(), activity.getBuckets());
        stockService.saveResult(event.getToken(), "FAIL:ACTIVE_ORDER_EXISTS");
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
