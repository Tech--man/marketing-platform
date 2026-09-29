package com.example.marketing.coupon.consumer;

import com.example.marketing.common.message.LocalMessageService;
import com.example.marketing.common.mq.CouponGrantEvent;
import com.example.marketing.common.mq.MqTopics;
import com.example.marketing.common.idempotent.BizKey;
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
 * <p>幂等与撞码是两件事（H6 起）：{@code user_coupon.request_id} 唯一索引挡重复投递
 * ——按 requestId 回查到券才确认；{@code uk_coupon_code} 撞码是券码随机空间碰撞，
 * 换码重试（上限 3 次）。意外异常抛出交给重试（RocketMQ 指数退避 / Stream 容器内
 * 3 次 + 本地消息表补偿）。</p>
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

    /** 券码撞码换码重试上限（理由见 insertCoupon 的 H6 注释） */
    private static final int CODE_COLLISION_RETRY = 3;

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
        // InnoDB 的唯一键冲突只回滚该语句、不中止事务，因此捕获后后续 confirm 仍可提交
        // （落库与确认合成单事务的吞吐理由见类注释）。
        boolean inserted = insertCoupon(event); // DuplicateKeyException 外抛 → 重投，不 confirm
        if (inserted) {
            Counter.builder("coupon.grant.persisted").register(meterRegistry).increment();
        } else {
            log.info("[grant-consumer] 重复消息幂等忽略 requestId={}", event.getRequestId());
        }
        // 无论首次还是重复，确认消息使补偿链路闭环；带 topic 定位，键拼法与登记侧一致
        localMessageService.confirm(MqTopics.TOPIC_COUPON_GRANT, BizKey.of("grant", event.getRequestId()));
    }

    @Transactional
    @Override
    public void onMessage(String payload) {
        handle(payload);
    }

    /**
     * 落库一张券。
     *
     * <p><b>H6（2026-09-29 架构审查）</b>：{@code user_coupon} 有两个唯一索引，撞哪一个的
     * 正确处置完全不同——{@code uk_request_id} 冲突是<b>重复投递</b>，返回 false 走幂等回放；
     * {@code uk_coupon_code} 冲突是<b>券码随机撞码</b>（CP+日期+[1e9,9e9) 随机），必须换码重试。
     * 原实现把两者混在同一个 catch 里一律"幂等忽略 + confirm"：撞码时券没落库、消息被确认、
     * Redis 库存与限领计数已被预扣且永不归还、用户查询永远 PROCESSING——全程零报错。
     * 区分手段：捕获后按 requestId 回查，查到券才算重复投递。</p>
     *
     * @return true = 本次真的插入了一张券；false = 重复投递的幂等回放
     * @throws DuplicateKeyException 重试上限内仍撞码、或撞上未提交的并发插入（交给重投）
     */
    private boolean insertCoupon(CouponGrantEvent event) {
        CouponTemplateEntity template = templateMapper.selectById(event.getTemplateId());
        if (template == null) {
            throw new IllegalStateException("券模板不存在: " + event.getTemplateId());
        }
        for (int attempt = 1; ; attempt++) {
            UserCouponEntity coupon = buildCoupon(event, template);
            try {
                userCouponMapper.insert(coupon);
                log.info("[grant-consumer] 落库成功 requestId={}, couponCode={}",
                        event.getRequestId(), coupon.getCouponCode());
                return true;
            } catch (DuplicateKeyException e) {
                if (findByRequestId(event.getRequestId()) != null) {
                    return false;
                }
                if (attempt >= CODE_COLLISION_RETRY) {
                    throw e;
                }
                Counter.builder("coupon.grant.code_collision").register(meterRegistry).increment();
                log.warn("[grant-consumer] 券码随机撞码，换码重试 requestId={}, attempt={}/{}",
                        event.getRequestId(), attempt, CODE_COLLISION_RETRY);
            }
        }
    }

    private UserCouponEntity findByRequestId(String requestId) {
        return userCouponMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<UserCouponEntity>()
                .eq(UserCouponEntity::getRequestId, requestId));
    }

    private UserCouponEntity buildCoupon(CouponGrantEvent event, CouponTemplateEntity template) {
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
        return coupon;
    }

    /** 券码：CP + yyyyMMdd + 10 位随机。撞码窗口 ~1e-9/张，3 连撞概率 ~1e-19，
     *  真到了就外抛交重投——绝不降级成"当重复消息确认掉" */
    private String generateCouponCode() {
        return "CP" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
    }
}
