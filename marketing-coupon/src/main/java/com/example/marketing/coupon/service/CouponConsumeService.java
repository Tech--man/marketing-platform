package com.example.marketing.coupon.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.coupon.domain.UserCouponStatus;
import com.example.marketing.coupon.dto.ConsumeRequest;
import com.example.marketing.coupon.infrastructure.entity.UserCouponEntity;
import com.example.marketing.coupon.infrastructure.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 券核销：Redisson 分布式锁 + 状态机 + 订单号幂等，三重防护避免重复核销。
 *
 * <p>锁粒度：单券（couponCode），分段避免全局锁；锁 wait 2s / lease 10s 防死锁，
 * DB 条件更新（status=UNUSED）作为锁失效时的最终兜底。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponConsumeService {

    private static final long LOCK_WAIT_SECONDS = 2;
    private static final long LOCK_LEASE_SECONDS = 10;

    private final UserCouponMapper userCouponMapper;
    private final RedissonClient redissonClient;

    /**
     * 核销券。返回核销后的券（重复核销同订单号返回原结果，实现幂等回放）。
     */
    public UserCouponEntity consume(ConsumeRequest request) {
        RLock lock = redissonClient.getLock("coupon:lock:" + request.couponCode());
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                throw new BizException(ErrorCode.DUPLICATE_REQUEST, "券正在处理中，请重试");
            }
            return doConsume(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.of(ErrorCode.SYSTEM_ERROR);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private UserCouponEntity doConsume(ConsumeRequest request) {
        UserCouponEntity coupon = userCouponMapper.selectOne(Wrappers.<UserCouponEntity>lambdaQuery()
                .eq(UserCouponEntity::getCouponCode, request.couponCode()));
        if (coupon == null) {
            throw new BizException(ErrorCode.BIZ_ERROR, "券不存在: " + request.couponCode());
        }
        if (!coupon.getUserId().equals(request.userId())) {
            throw new BizException(ErrorCode.BIZ_ERROR, "券不属于该用户");
        }
        UserCouponStatus status = UserCouponStatus.valueOf(coupon.getStatus());
        if (status == UserCouponStatus.USED) {
            // 幂等回放：同订单号重复核销返回成功，异订单号拒绝
            if (request.orderNo().equals(coupon.getOrderNo())) {
                return coupon;
            }
            throw new BizException(ErrorCode.COUPON_STATUS_INVALID, "券已核销");
        }
        if (status == UserCouponStatus.EXPIRED || coupon.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.COUPON_STATUS_INVALID, "券已过期");
        }
        // 条件更新兜底：即使锁失效，DB 层 UNUSED→USED 也只会有一个成功
        coupon.setStatus(UserCouponStatus.USED.name());
        coupon.setOrderNo(request.orderNo());
        coupon.setUseTime(LocalDateTime.now());
        int updated = userCouponMapper.update(null, Wrappers.<UserCouponEntity>lambdaUpdate()
                .eq(UserCouponEntity::getId, coupon.getId())
                .eq(UserCouponEntity::getStatus, UserCouponStatus.UNUSED.name())
                .set(UserCouponEntity::getStatus, UserCouponStatus.USED.name())
                .set(UserCouponEntity::getOrderNo, request.orderNo())
                .set(UserCouponEntity::getUseTime, coupon.getUseTime()));
        if (updated == 0) {
            throw new BizException(ErrorCode.COUPON_STATUS_INVALID, "券已核销（并发冲突）");
        }
        log.info("[consume] 核销成功 couponCode={}, orderNo={}", request.couponCode(), request.orderNo());
        return coupon;
    }

    /** 查询用户可用券列表 */
    public java.util.List<UserCouponEntity> listUserUsableCoupons(Long userId) {
        return userCouponMapper.selectList(Wrappers.<UserCouponEntity>lambdaQuery()
                .eq(UserCouponEntity::getUserId, userId)
                .eq(UserCouponEntity::getStatus, UserCouponStatus.UNUSED.name())
                .gt(UserCouponEntity::getExpireAt, LocalDateTime.now()));
    }
}
