package com.example.marketing.coupon.service;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 领券前的活动参与闸（2026-09-29 审查第五批）：校验父活动的状态与灰度。
 *
 * <p>原先这层只存在于 H5 前端（调 {@code participatable}/{@code gray-hit} 决定是否
 * 展示入口），直接 {@code POST /api/coupon/grant} 的调用方完全绕过——活动
 * OFFLINE/FINISHED 后券照发、灰度 5% 放量对任何登录用户无效。真值在 activity 进程
 * （库 + GrayRuleCache），券进程读不到：这里读 activity 侧
 * {@code ActivityGatePublisher} 镜像进 Redis 的两把键。</p>
 *
 * <p><b>键缺失 = 放行（fail-open）</b>：activity 模块尚未部署本版本、或发布器
 * 尚未跑完第一轮时，行为与旧版完全一致——总不能因为加了闸门把存量环境的领券全拒了。
 * 键一旦存在即强制：非 ONLINE 拒、灰度不命中拒。灰度判定公式与 activity 侧
 * {@code GrayService.hit} 逐字相同（白名单直通，否则 floorMod(uid,100)&lt;percent；
 * percent 为 {@code -} 表示未配=全量），两处漂移等于两种人看到两个活动。</p>
 */
@Slf4j
@Service
public class ActivityGate {

    private final StringRedisTemplate redis;

    public ActivityGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @throws BizException 活动不可参与（41007）或灰度不命中（41000，文案区分）
     */
    public void checkGrantable(String activityNo, Long userId) {
        if (activityNo == null || activityNo.isBlank()) {
            return; // 模板没挂活动：领券资格由模板自身状态/时间窗控制，本闸不管
        }
        // W2.1（2026-09-30 第二轮复审）：状态值形状升级为 status|version（发布侧 Lua CAS
        // 用版本比大小防旧快照回滚）。消费侧只取状态段；旧形状（裸 status，迁移期
        // activity 旧代码写的值）整串即状态，语义不变。
        String raw = redis.opsForValue().get("activity:gate:status:" + activityNo);
        String status = raw;
        if (raw != null) {
            int sep = raw.indexOf('|');
            if (sep >= 0) {
                status = raw.substring(0, sep);
            }
        }
        // P1（2026-09-30 第二轮复审）：可参与 = ONLINE <b>或 GRAY</b>——activity 侧
        // ActivityStatus.participatable() 本来就含 GRAY（状态机的正规路径
        // AUDITING--APPROVE--&gt;GRAY），只认 ONLINE 等于灰度放量阶段对所有用户
        // （含白名单内测账号）关闭领券。GRAY 继续走下面的灰度命中判定放量；
        // OFFLINE/FINISHED/DRAFT/AUDITING 照旧拒绝。
        if (status != null && !status.isBlank() && !"ONLINE".equals(status) && !"GRAY".equals(status)) {
            throw BizException.of(ErrorCode.ACTIVITY_NOT_ONLINE,
                    "活动 " + activityNo + " 当前状态 " + status + "，不可参与");
        }
        String gray = redis.opsForValue().get("activity:gate:gray:" + activityNo);
        if (gray == null || gray.isBlank()) {
            return;
        }
        int sep = gray.indexOf('|');
        if (sep < 0) {
            return; // 形状不认识：放行并留日志，别让格式演化把领券全堵死
        }
        String percentPart = gray.substring(0, sep);
        if ("-".equals(percentPart)) {
            return; // 未配灰度 = 全量（与 GrayService 的语义一致）
        }
        // P1（2026-09-30 第二轮复审）：白名单解析与 activity 侧 GrayRuleCache.parseWhitelist
        // 同口径——逐项 trim + 坏项跳过。原实现裸 parseLong："70001, 70002"（自家测试都
        // 用的带空格格式，activity 侧容错接受）会让该活动<b>所有</b>领券请求 NFE→50000。
        Set<Long> whitelist = new java.util.HashSet<>();
        for (String part : gray.substring(sep + 1).split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                whitelist.add(Long.parseLong(trimmed));
            } catch (NumberFormatException bad) {
                log.warn("[activity-gate] 灰度白名单非数字项已跳过（与 GrayRuleCache 同口径）: {}",
                        trimmed);
            }
        }
        if (userId != null && whitelist.contains(userId)) {
            return;
        }
        long uid = userId == null ? 0L : userId;
        if (Math.floorMod(uid, 100L) >= Long.parseLong(percentPart)) {
            throw BizException.of(ErrorCode.BIZ_ERROR,
                    "活动 " + activityNo + " 灰度放量 " + percentPart + "%，暂未对你开放");
        }
    }
}
