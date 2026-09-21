package com.example.marketing.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.message.LocalMessageService;
import com.example.marketing.common.mq.MqTopics;
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
        String bizKey = "seckill:" + token;
        // 本地消息表 + MQ：即使进程崩溃，补偿定时器会把消息补发出去
        localMessageService.recordIfAbsent(MqTopics.TOPIC_SECKILL_ORDER, MqTopics.TAG_ORDER,
                bizKey, JsonUtils.toJson(event));
        localMessageService.publish(bizKey);
        stockService.saveResult(token, "ACCEPTED");

        Counter.builder("seckill.grab.accepted").register(meterRegistry).increment();
        log.info("[seckill] 抢购受理 activityNo={}, userId={}, bucket={}, token={}",
                activityNo, userId, bucket, token);
        return new GrabTicket(token, "ACCEPTED");
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
