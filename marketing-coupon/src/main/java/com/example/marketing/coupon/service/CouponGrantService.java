package com.example.marketing.coupon.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.idempotent.IdempotentExecutor;
import com.example.marketing.common.idempotent.BizKey;
import com.example.marketing.common.message.LocalMessageService;
import com.example.marketing.common.mq.CouponGrantEvent;
import com.example.marketing.common.mq.MqTopics;
import com.example.marketing.common.util.JsonUtils;
import com.example.marketing.coupon.dto.GrantRequest;
import com.example.marketing.coupon.dto.GrantResultVO;
import com.example.marketing.coupon.dto.GrantTicket;
import com.example.marketing.coupon.infrastructure.entity.CouponTemplateEntity;
import com.example.marketing.coupon.infrastructure.entity.UserCouponEntity;
import com.example.marketing.coupon.infrastructure.mapper.UserCouponMapper;
import com.example.marketing.openapi.risk.RiskCheckService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 领券编排：风控 → 模板校验 → Redis Lua 原子预扣 → 本地消息表 + MQ → 异步落库。
 *
 * <p>幂等三层：IdempotentExecutor（bizKey=grant:requestId）+ 本地消息表 biz_key 唯一索引
 * + user_coupon.request_id 唯一索引，保证"客户端重试 / MQ 重投"下最多成功一次。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponGrantService {

    private static final String SCENE = "COUPON_GRANT";

    private final IdempotentExecutor idempotentExecutor;
    private final CouponTemplateService templateService;
    private final CouponStockService stockService;
    private final LocalMessageService localMessageService;
    private final RiskCheckService riskCheckService;
    private final UserCouponMapper userCouponMapper;
    private final MeterRegistry meterRegistry;

    /**
     * 发起领券（同步返回受理凭证，券码经结果查询接口获取）。
     */
    public GrantTicket grant(GrantRequest request) {
        String bizKey = "grant:" + request.requestId();
        return idempotentExecutor.execute(bizKey, GrantTicket.class, () -> doGrant(request));
    }

    /**
     * 查询领券结果：SUCCESS 带券码；无券且未失败则 PROCESSING。
     *
     * <p>按 {@code uid} 一起查。requestId 是客户端生成的 UUID，只按它查的话，
     * 拿到别人 requestId 的人就能读走那枚<b>券码</b> —— 券码是可兑付的，
     * 这不是"看到别人的进度"而是"捡到别人的券"。
     * 查不到时仍返回 PROCESSING：这里不区分"没这条"和"不是你的"，
     * 免得把接口变成一个 requestId 存在性探针。</p>
     */
    public GrantResultVO queryResult(String requestId, long uid) {
        UserCouponEntity coupon = userCouponMapper.selectOne(Wrappers.<UserCouponEntity>lambdaQuery()
                .eq(UserCouponEntity::getRequestId, requestId)
                .eq(UserCouponEntity::getUserId, uid));
        if (coupon != null) {
            return GrantResultVO.success(coupon.getCouponCode());
        }
        if (idempotentExecutor.isDone("grant:" + requestId)) {
            // 受理成功但券未落库：仍在削峰队列中
            return GrantResultVO.processing();
        }
        return GrantResultVO.processing();
    }

    private GrantTicket doGrant(GrantRequest request) {
        // 1. 风控（占位放行；生产接入黑名单/行为规则，异常时降级放行）
        RiskCheckService.RiskCheckResult risk =
                riskCheckService.check(SCENE, request.userId(), request.templateNo());
        if (!risk.pass()) {
            Counter.builder("coupon.grant.risk.rejected").register(meterRegistry).increment();
            throw new BizException(ErrorCode.RISK_REJECTED, risk.reason());
        }
        // 2. 模板校验（状态 + 时间窗）
        CouponTemplateEntity template = templateService.getRequiringGrantable(request.templateNo());
        // 3. Redis 原子预扣（库存 + 个人限领）
        java.time.Duration userKeyTtl = CouponStockService.userKeyTtl(template.getEndTime());
        CouponStockService.DeductResult deduct = stockService.deduct(
                template.getId(), request.userId(), 1, template.getPerUserLimit(), userKeyTtl);
        if (deduct == CouponStockService.DeductResult.NOT_WARMED) {
            templateService.warmStock(template);
            deduct = stockService.deduct(template.getId(), request.userId(), 1,
                    template.getPerUserLimit(), userKeyTtl);
        }
        switch (deduct) {
            case SOLD_OUT -> {
                Counter.builder("coupon.grant.sold_out").register(meterRegistry).increment();
                throw BizException.of(ErrorCode.STOCK_NOT_ENOUGH);
            }
            case EXCEED_LIMIT -> throw new BizException(ErrorCode.BIZ_ERROR, "已超过单人限领数量");
            case NOT_WARMED -> throw BizException.of(ErrorCode.SYSTEM_ERROR);
            default -> {
                // 4. 本地消息表 + MQ（发送失败由补偿定时器重发；消费端幂等落库）
                CouponGrantEvent event = new CouponGrantEvent(request.requestId(), request.userId(),
                        template.getId(), template.getActivityNo(), 1);
                // 与幂等表同一个键（BizKey 统一拼法），消费端 confirm 能从事件里原样还原
                String msgKey = BizKey.of("grant", request.requestId());
                try {
                    localMessageService.recordIfAbsent(MqTopics.TOPIC_COUPON_GRANT, MqTopics.TAG_GRANT,
                            msgKey, JsonUtils.toJson(event));
                    localMessageService.publish(MqTopics.TOPIC_COUPON_GRANT, msgKey);
                } catch (RuntimeException e) {
                    // A1/A2（2026-09-29 审查，根因 A：预扣后无归还原语）：预扣之后、
                    // 消息登记/投递之前的失败必须归还预扣——否则幂等键标 FAILED、客户端
                    // 重试整体重跑 action 再扣一次（1 张券吃 2 份库存 + 2 次限领，第二次
                    // 多半直接 EXCEED_LIMIT，用户一张都拿不到）。归还后重试从头来，
                    // 预扣最多生效一次。进程在两步之间被 kill 的残余窗口 try/catch 管不到，
                    // 由 ④ 的 coupon mismatch 恒等式兜底可见（残留为库存偏少方向）。
                    rollbackPreDeduct(template.getId(), request.userId(), 1);
                    throw e;
                }
                Counter.builder("coupon.grant.accepted").register(meterRegistry).increment();
                return GrantTicket.accepted(request.requestId());
            }
        }
        // switch 各分支均已 return/throw，此处不可达，无需兜底语句
    }

    /** 归还预扣（rollback_stock.lua：INCRBY 库存 + DECRBY 个人限领计数）。
     *  归还自身失败只能计数暴露——别让补偿动作把原始异常吃掉 */
    private void rollbackPreDeduct(Long templateId, Long userId, int quantity) {
        try {
            stockService.rollback(templateId, userId, quantity);
            Counter.builder("coupon.grant.rollback").register(meterRegistry).increment();
            log.warn("[grant] 预扣后失败，已归还预扣 template={}, user={}", templateId, userId);
        } catch (RuntimeException rollbackEx) {
            Counter.builder("coupon.grant.rollback_failed").register(meterRegistry).increment();
            log.error("[grant] 预扣归还失败（残余差值由 ④ coupon mismatch 暴露）template={}, user={}: {}",
                    templateId, userId, rollbackEx.toString());
        }
    }
}
