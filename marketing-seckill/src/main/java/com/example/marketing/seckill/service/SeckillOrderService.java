package com.example.marketing.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.seckill.domain.SeckillOrderStatus;
import com.example.marketing.seckill.infrastructure.entity.SeckillOrderEntity;
import com.example.marketing.seckill.infrastructure.mapper.SeckillActivityMapper;
import com.example.marketing.seckill.infrastructure.mapper.SeckillOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 秒杀订单操作：模拟支付回调 + 超时取消（DB 条件更新兜底状态竞争）。
 *
 * <p>状态流转一律用 {@code UPDATE ... WHERE status='CREATED'} 条件更新表达，
 * 更新 0 行即状态已被并发改变（已支付/已取消），据此做幂等与冲突反馈。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillOrderService {

    private final SeckillOrderMapper orderMapper;
    private final SeckillActivityMapper activityMapper;
    private final SeckillStockService stockService;

    /** 模拟支付回调：CREATED → PAID；已 PAID 幂等成功；已 CANCELLED 拒绝 */
    public SeckillOrderEntity pay(String orderNo) {
        SeckillOrderEntity order = require(orderNo);
        if (SeckillOrderStatus.PAID.name().equals(order.getStatus())) {
            return order; // 支付回调重复投递，幂等返回
        }
        int updated = orderMapper.update(null, new UpdateWrapper<SeckillOrderEntity>()
                .eq("order_no", orderNo)
                .eq("status", SeckillOrderStatus.CREATED.name())
                .set("status", SeckillOrderStatus.PAID.name())
                .set("pay_time", LocalDateTime.now()));
        if (updated == 0) {
            throw new BizException(ErrorCode.COUPON_STATUS_INVALID, "订单已取消或状态不允许支付");
        }
        log.info("[seckill] 支付成功 orderNo={}", orderNo);
        return require(orderNo);
    }

    /**
     * 超时取消（定时器调用）：条件更新抢到取消权后回补 Redis 库存与已售数。
     *
     * @return true 表示本次调用完成了取消
     */
    public boolean cancelTimeout(SeckillOrderEntity order, int buckets) {
        int updated = orderMapper.update(null, new UpdateWrapper<SeckillOrderEntity>()
                .eq("id", order.getId())
                .eq("status", SeckillOrderStatus.CREATED.name())
                .set("status", SeckillOrderStatus.CANCELLED.name()));
        if (updated == 0) {
            return false; // 已被支付或已被其他实例取消
        }
        boolean refilled = stockService.refill(order.getActivityNo(), order.getUserId(),
                order.getBucket() == null ? 1 : order.getBucket(), buckets);
        // 无论 Redis 回补是否生效（桶 key 可能已过期），DB 已售数都要回减，保持账实口径
        activityMapper.update(null, new UpdateWrapper<com.example.marketing.seckill.infrastructure.entity.SeckillActivityEntity>()
                .eq("activity_no", order.getActivityNo())
                .setSql("sold_stock = GREATEST(sold_stock - 1, 0)"));
        if (!refilled) {
            log.warn("[seckill] 超时取消但 Redis 桶已过期，仅回减 DB 已售数 orderNo={}", order.getOrderNo());
        }
        return true;
    }

    private SeckillOrderEntity require(String orderNo) {
        SeckillOrderEntity order = orderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrderEntity>().eq(SeckillOrderEntity::getOrderNo, orderNo));
        if (order == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "订单不存在: " + orderNo);
        }
        return order;
    }
}
