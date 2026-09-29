package com.example.marketing.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.message.LocalMessageService;
import com.example.marketing.common.mq.MqTopics;
import com.example.marketing.common.idempotent.BizKey;
import com.example.marketing.common.mq.SeckillOrderEvent;
import com.example.marketing.common.util.JsonUtils;
import com.example.marketing.openapi.risk.RiskCheckService;
import com.example.marketing.seckill.config.SeckillProperties;
import com.example.marketing.seckill.dto.GrabTicket;
import com.example.marketing.seckill.infrastructure.entity.SeckillActivityEntity;
import com.example.marketing.seckill.infrastructure.mapper.SeckillActivityMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 抢购编排：活动校验 → 风控 → Lua 原子占名额 → 本地消息表 + MQ 异步下单 → 返回排队 token。
 *
 * <p>同步路径只有一次 Redis Lua（毫秒级），DB 写全部推到 MQ 之后，
 * 单活动 50 万 QPS 的洪峰被限流网关 + 分桶 Lua + MQ 削峰三层消化。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillGrabService {

    private final SeckillActivityMapper activityMapper;
    private final SeckillStockService stockService;
    private final LocalMessageService localMessageService;
    private final RiskCheckService riskCheckService;
    private final SeckillProperties properties;
    private final MeterRegistry meterRegistry;

    public GrabTicket grab(String activityNo, Long userId) {
        SeckillActivityEntity activity = requireOnline(activityNo);

        var risk = riskCheckService.check("seckill", userId, activityNo);
        if (!risk.pass()) {
            Counter.builder("seckill.grab.risk_rejected").register(meterRegistry).increment();
            throw new BizException(ErrorCode.RISK_REJECTED, risk.reason());
        }

        int buckets = activity.getBuckets() == null ? properties.getBuckets() : activity.getBuckets();
        // 原子占名额（失败抛 BizException，同步反馈售罄/重复）
        int bucket = stockService.grab(activityNo, userId, buckets);

        String token = UUID.randomUUID().toString().replace("-", "");
        SeckillOrderEvent event = new SeckillOrderEvent(token, activityNo, userId, activity.getItemId(), bucket);
        String bizKey = BizKey.of("seckill", token);
        try {
            // 顺序很关键：受理占位必须在投递之前，否则消费端可能先写出终态、再被这里的
            // ACCEPTED 覆盖（markAccepted 自身也只允许"无值时写"，双保险）
            stockService.markAccepted(token);
            // 本地消息表 + MQ：即使进程崩溃，补偿定时器会把消息补发出去
            localMessageService.recordIfAbsent(MqTopics.TOPIC_SECKILL_ORDER, MqTopics.TAG_ORDER,
                    bizKey, JsonUtils.toJson(event));
            localMessageService.publish(MqTopics.TOPIC_SECKILL_ORDER, bizKey);
        } catch (RuntimeException e) {
            // A4（2026-09-29 审查，根因 A）：占名额之后、消息登记/投递之前的失败必须回补
            // 名额并删防重标记——否则名额泄漏 + 用户被 bought 标记锁死至 TTL（轮询永远
            // ACCEPTED→过期，拿不到单也抢不了第二次）。refill 内部即回补原桶 + 删标记；
            // 它失败时只能计数暴露（残余由 seckill mismatch 恒等式兜底可见）。
            // 进程在两步之间被 kill 的残余窗口 try/catch 管不到，同理靠 mismatch 暴露。
            compensateGrab(activityNo, userId, bucket, buckets, token);
            throw e;
        }

        Counter.builder("seckill.grab.accepted").register(meterRegistry).increment();
        log.info("[seckill] 抢购受理 activityNo={}, userId={}, bucket={}, token={}",
                activityNo, userId, bucket, token);
        return new GrabTicket(token, "ACCEPTED");
    }

    /** 抢购受理失败的最佳努力补偿：回补名额 + 删防重标记 + 写 FAIL 终态（轮询别停在 ACCEPTED） */
    private void compensateGrab(String activityNo, Long userId, int bucket, int buckets, String token) {
        try {
            stockService.refill(activityNo, userId, bucket, buckets);
            stockService.saveResult(token, "FAIL:GRAB_ABORTED");
            Counter.builder("seckill.grab.compensated").register(meterRegistry).increment();
            log.warn("[seckill] 占名额后失败，已回补名额并写 FAIL activityNo={}, userId={}, bucket={}",
                    activityNo, userId, bucket);
        } catch (RuntimeException refillEx) {
            Counter.builder("seckill.grab.compensate_failed").register(meterRegistry).increment();
            log.error("[seckill] 回补失败（残余差值由 ④ seckill mismatch 暴露）activityNo={}, userId={}, "
                    + "bucket={}: {}", activityNo, userId, bucket, refillEx.toString());
        }
    }

    /** 轮询抢购结果：ACCEPTED / SUCCESS:{orderNo} / FAIL:{reason} / null（过期视为未中） */
    public String queryResult(String token) {
        return stockService.getResult(token);
    }

    private SeckillActivityEntity requireOnline(String activityNo) {
        SeckillActivityEntity activity = activityMapper.selectOne(
                new LambdaQueryWrapper<SeckillActivityEntity>()
                        .eq(SeckillActivityEntity::getActivityNo, activityNo));
        if (activity == null || !"ONLINE".equals(activity.getStatus())) {
            throw BizException.of(ErrorCode.ACTIVITY_NOT_ONLINE);
        }
        LocalDateTime now = LocalDateTime.now();
        if ((activity.getStartTime() != null && now.isBefore(activity.getStartTime()))
                || (activity.getEndTime() != null && now.isAfter(activity.getEndTime()))) {
            throw BizException.of(ErrorCode.ACTIVITY_NOT_ONLINE, "不在秒杀时间窗口内");
        }
        return activity;
    }
}
