package com.example.marketing.coupon.consumer;

import com.example.marketing.common.message.LocalMessageService;
import com.example.marketing.common.mq.CouponGrantEvent;
import com.example.marketing.common.mq.MqTopics;
import com.example.marketing.common.mq.StreamMessageHandler;
import com.example.marketing.common.util.JsonUtils;
import com.example.marketing.coupon.domain.UserCouponStatus;
import com.example.marketing.coupon.infrastructure.entity.CouponTemplateEntity;
import com.example.marketing.coupon.infrastructure.entity.UserCouponEntity;
import com.example.marketing.coupon.infrastructure.mapper.CouponTemplateMapper;
import com.example.marketing.coupon.infrastructure.mapper.UserCouponMapper;
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
import java.util.concurrent.ThreadLocalRandom;

/**
 * 领券消息消费者：MQ 削峰后异步落库（最终一致的"落库"环节）。
 *
 * <p>双部署形态：Full 形态由 {@code @RocketMQMessageListener} 容器驱动 onMessage；
 * Lite 形态（redis-stream）由 StreamConsumerRegistrar 轮询 XREADGROUP 驱动 handle，
 * 两者复用同一段落库逻辑。</p>
 *
 * <p>幂等：user_coupon.request_id 唯一索引，重复投递捕获 DuplicateKeyException 直接确认；
 * 意外异常抛出交给重试（RocketMQ 指数退避 / Stream 容器内 3 次 + 本地消息表补偿）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.TOPIC_COUPON_GRANT,
        selectorExpression = MqTopics.TAG_GRANT,
        consumerGroup = MqTopics.GROUP_COUPON,
        consumeThreadNumber = 8)
public class CouponGrantConsumer implements RocketMQListener<String>, StreamMessageHandler {

    private final UserCouponMapper userCouponMapper;
    private final CouponTemplateMapper templateMapper;
    private final LocalMessageService localMessageService;
    private final MeterRegistry meterRegistry;

    @Override
    public String topic() {
        return MqTopics.TOPIC_COUPON_GRANT;
    }

    @Override
    public String group() {
        return MqTopics.GROUP_COUPON;
    }

    /**
     * 落库与确认合成一个事务：原本 insert 与 confirm 各自 autocommit，每条消息要等两次
     * InnoDB redo fsync —— 实测这决定了 LITE 消费端 ~22 msg/s 的天花板（A-B-A 对照验证）。
     *
     * <p>{@code onMessage} 也必须标注：它是 RocketMQ 容器的入口，内部再调 {@code handle}
     * 属于自调用、绕过代理，只标 handle 会让 Full 形态静默没有事务。</p>
     */
    @Transactional
    @Override
    public void handle(String payload) {
        CouponGrantEvent event = JsonUtils.parse(payload, CouponGrantEvent.class);
        try {
            insertCoupon(event);
            Counter.builder("coupon.grant.persisted").register(meterRegistry).increment();
        } catch (DuplicateKeyException e) {
            // InnoDB 的唯一键冲突只回滚该语句、不中止事务，因此后续 confirm 仍可提交
            log.info("[grant-consumer] 重复消息幂等忽略 requestId={}", event.getRequestId());
        }
        // 无论首次还是重复，确认消息使补偿链路闭环
        localMessageService.confirm(event.getRequestId());
    }

    @Transactional
    @Override
    public void onMessage(String payload) {
        handle(payload);
    }

    private void insertCoupon(CouponGrantEvent event) {
        CouponTemplateEntity template = templateMapper.selectById(event.getTemplateId());
        if (template == null) {
            throw new IllegalStateException("券模板不存在: " + event.getTemplateId());
        }
        LocalDateTime now = LocalDateTime.now();
        UserCouponEntity coupon = new UserCouponEntity();
        coupon.setCouponCode(generateCouponCode());
        coupon.setTemplateId(template.getId());
        coupon.setTemplateNo(template.getTemplateNo());
        coupon.setActivityNo(template.getActivityNo());
        coupon.setUserId(event.getUserId());
        coupon.setStatus(UserCouponStatus.UNUSED.name());
        coupon.setCouponType(template.getCouponType());
        coupon.setFaceValue(template.getFaceValue());
        coupon.setThresholdAmount(template.getThresholdAmount());
        coupon.setValidStart(now);
        coupon.setExpireAt(now.plusDays(template.getValidDays() == null ? 7 : template.getValidDays()));
        coupon.setRequestId(event.getRequestId());
        coupon.setGrantTime(now);
        userCouponMapper.insert(coupon);
        log.info("[grant-consumer] 落库成功 requestId={}, couponCode={}", event.getRequestId(), coupon.getCouponCode());
    }

    /** 券码：CP + yyyyMMdd + 10 位随机（业务唯一索引兜底冲突重试在上游） */
    private String generateCouponCode() {
        return "CP" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
    }
}
