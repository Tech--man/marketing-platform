package com.example.marketing.coupon.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.marketing.coupon.domain.UserCouponStatus;
import com.example.marketing.coupon.infrastructure.entity.UserCouponEntity;
import com.example.marketing.coupon.infrastructure.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 券过期定时任务：UNUSED 且已过 expire_at → EXPIRED。
 *
 * <p>生产环境（演进路线）：替换为 XXL-Job 分片 + 按 user_id 路由，避免大表扫描；
 * 此处 batch limit 循环保证单轮可控。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponExpireJob {

    private static final int BATCH_SIZE = 500;

    private final UserCouponMapper userCouponMapper;

    @Scheduled(fixedDelayString = "${marketing.coupon.expire-interval-ms:60000}")
    public void expire() {
        int total = 0;
        while (true) {
            List<UserCouponEntity> batch = userCouponMapper.selectList(
                    Wrappers.<UserCouponEntity>lambdaQuery()
                            .eq(UserCouponEntity::getStatus, UserCouponStatus.UNUSED.name())
                            .lt(UserCouponEntity::getExpireAt, LocalDateTime.now())
                            .last("LIMIT " + BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            int updated = userCouponMapper.update(null, Wrappers.<UserCouponEntity>lambdaUpdate()
                    .in(UserCouponEntity::getId, batch.stream().map(UserCouponEntity::getId).toList())
                    .eq(UserCouponEntity::getStatus, UserCouponStatus.UNUSED.name())
                    .set(UserCouponEntity::getStatus, UserCouponStatus.EXPIRED.name()));
            total += updated;
            if (batch.size() < BATCH_SIZE) {
                break;
            }
        }
        if (total > 0) {
            log.info("[coupon-expire] 本轮过期 {} 张券", total);
        }
    }
}
