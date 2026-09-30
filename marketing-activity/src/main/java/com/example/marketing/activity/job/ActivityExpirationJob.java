package com.example.marketing.activity.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.marketing.activity.domain.ActivityEvent;
import com.example.marketing.activity.domain.ActivityStatus;
import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import com.example.marketing.activity.infrastructure.mapper.ActivityMapper;
import com.example.marketing.activity.service.ActivityService;
import com.example.marketing.common.schedule.RedisLeaseLock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动到期收口 Job（W2.4，2026-09-30 第二轮复审）。
 *
 * <p><b>为什么需要它</b>：endTime 字段此前全链路无人引用——活动到了结束时间仍是
 * ONLINE/GRAY，预算照扣、券照发（若模板窗口未到期）、gate 镜像继续放行。这个 Job
 * 每分钟把「ONLINE/GRAY 且 endTime 已过」的活动走状态机 FINISH：数据本身对了，
 * gate 发布器镜像（status|version CAS）、优惠规则可见性等一切下游自然收敛。</p>
 *
 * <p><b>逐单隔离</b>：一条活动的流转异常（version 冲突等）只能污染它自己——循环外抛
 * 会把整轮扫描冲掉，毒行每分钟重选重炸（第七批秒杀 Job 的教训）。FINISH 是终态
 * 迁移，下一轮扫描自然不再命中本行；流转撞乐观锁（version 被并发编辑推进）也只
 * 是本轮跳过、下一轮再试。</p>
 *
 * <p>多实例由 {@link RedisLeaseLock} 收敛；无 endTime 的长尾活动不扫（isNotNull 过滤）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityExpirationJob {

    private final ActivityMapper activityMapper;
    private final ActivityService activityService;
    private final MeterRegistry meterRegistry;
    private final RedisLeaseLock leaseLock;

    @Scheduled(fixedDelayString = "${marketing.activity.expiration-scan-interval-ms:60000}")
    public void finishExpired() {
        leaseLock.runExclusive("activity-expiration", Duration.ofSeconds(55), this::doFinish);
    }

    private void doFinish() {
        List<ActivityEntity> expired = activityMapper.selectList(
                Wrappers.<ActivityEntity>lambdaQuery()
                        .in(ActivityEntity::getStatus,
                                ActivityStatus.ONLINE.name(), ActivityStatus.GRAY.name())
                        .isNotNull(ActivityEntity::getEndTime)
                        .lt(ActivityEntity::getEndTime, LocalDateTime.now()));
        if (expired.isEmpty()) {
            return;
        }
        for (ActivityEntity activity : expired) {
            try {
                activityService.transition(activity.getActivityNo(), ActivityEvent.FINISH);
                Counter.builder("activity.expiration.finished").register(meterRegistry).increment();
                log.info("[activity] 到期自动结束 {}（endTime={}）",
                        activity.getActivityNo(), activity.getEndTime());
            } catch (Exception e) {
                // 乐观锁冲突（version 被并发推进）是最常见的良性失败：下一轮再试
                Counter.builder("activity.expiration.failed").register(meterRegistry).increment();
                log.warn("[activity] 到期结束失败（跳过该行，下轮再试）activityNo={}: {}",
                        activity.getActivityNo(), e.toString());
            }
        }
    }
}
