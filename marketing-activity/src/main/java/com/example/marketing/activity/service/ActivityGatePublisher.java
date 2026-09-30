package com.example.marketing.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import com.example.marketing.activity.infrastructure.mapper.ActivityMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 活动参与闸门的跨服务发布器（2026-09-29 审查第五批）。
 *
 * <p>为什么需要它：活动状态与灰度原先只在 activity 进程内有效（状态机查 DB、灰度走
 * {@link GrayRuleCache}），H5 靠前端调 {@code participatable}/{@code gray-hit} 决定
 * 是否<b>展示</b>入口——直接 {@code POST /api/coupon/grant} 的调用方完全绕过这层。
 * 券服务又读不到 activity 的库（每服务一库档根本没有那张表），跨服务 HTTP 也被
 * 架构禁止。与审计总线同一手法：owning 进程把判定所需的<b>最小事实</b>镜像进共享
 * Redis，消费侧只读不解释。</p>
 *
 * <p>发布内容每轮全量重写（SET 幂等）：状态键 {@code activity:gate:status:{no}} 与
 * 灰度键 {@code activity:gate:gray:{no}=percent|w1,w2}。无 TTL：这是状态镜像不是
 * 缓存，删键等于"回到不设防"。回源周期与 GrayRuleCache 同档（默认 5s），
 * DB 改列后一个周期内收敛；无活动时也写空——消费侧见键缺失按 fail-open
 * （见 coupon 侧 {@code ActivityGate} 的注释，那是迁移期的明确取舍）。</p>
 *
 * <p>只在 activity 模块（含 standalone 聚合）启动；admin/网关等进程没有
 * ActivityMapper，组件扫描天然不会装配。多副本各自全量重写同一组键，
 * 值同源（DB）故无冲突。</p>
 */
@Slf4j
@Component
public class ActivityGatePublisher {

    public static final String STATUS_KEY_PREFIX = "activity:gate:status:";
    public static final String GRAY_KEY_PREFIX = "activity:gate:gray:";
    /** 灰度键值形状：{@code percent|uid1,uid2}；percent 为 {@code -} 表示列 NULL（未配=全量） */
    public static final String GRAY_NO_RULE = "-";
    /**
     * 状态键值形状（W2.1，2026-09-30 第二轮复审）：{@code status|version}。
     * version 取 activity 行的乐观锁值（每次流转/编辑 +1），写入经 gate_cas.lua
     * 比大小——旧快照（publishAll 的 SELECT 与 SET 之间发生了流转）不再能把
     * 已发布的新状态覆盖回去，OFFLINE 预案不被 5 秒旧值回滚。
     */
    private static final org.springframework.data.redis.core.script.RedisScript<Long> STATUS_CAS =
            com.example.marketing.common.redis.LuaScripts.ofLong("lua/gate_cas.lua");

    private final ActivityMapper activityMapper;
    private final StringRedisTemplate redis;
    private final long refreshSeconds;
    private volatile ScheduledExecutorService scheduler;

    public ActivityGatePublisher(ActivityMapper activityMapper, StringRedisTemplate redis,
                                 @Value("${marketing.activity.gate-refresh-seconds:5}") long refreshSeconds) {
        this.activityMapper = activityMapper;
        this.redis = redis;
        this.refreshSeconds = Math.max(1L, refreshSeconds);
    }

    @PostConstruct
    public void start() {
        publishAll();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mkt-activity-gate");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::publishAll, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
        log.info("[activity-gate] 参与闸门发布器启动，回源周期 {}s", refreshSeconds);
    }

    @PreDestroy
    public void stop() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
    }

    /** 全量重写当前所有活动的两把键。任务自身吞异常：发布失败下一轮再来 */
    void publishAll() {
        try {
            List<ActivityEntity> snapshot = activityMapper.selectList(
                    Wrappers.<ActivityEntity>lambdaQuery().select(ActivityEntity::getActivityNo));
            for (ActivityEntity marker : snapshot) {
                // W2.1：逐条即时重读——快照只用来定"有哪些活动"，行内容以发布时刻的
                // 单行读为准（窗口从全表快照级缩到毫秒级），配合状态值的 version CAS
                // 双保险：即便这毫秒里又发生流转，旧 version 的写入也会被 Lua 拒绝。
                publishNow(marker.getActivityNo());
            }
        } catch (RuntimeException e) {
            log.warn("[activity-gate] 本轮发布失败（下轮再来，消费侧按旧值/fail-open）: {}", e.toString());
        }
    }

    /** 单活动即时发布：状态机流转后调用，让下线/暂停在一个周期内生效而不是等回源。
     *  <b>自身吞异常</b>（P2，2026-09-30 第二轮复审）：它跑在 transition/updateGray 的
     *  事务边界内，Redis 抖动时外抛会把已校验通过的状态变更一起回滚——"下线预案执行
     *  失败"恰恰是最不该发生的路径。失败留给周期全量重写兜底（DB 才是权威）。 */
    void publishNow(String activityNo) {
        try {
            ActivityEntity activity = activityMapper.selectOne(
                    Wrappers.<ActivityEntity>lambdaQuery().eq(ActivityEntity::getActivityNo, activityNo));
            if (activity == null) {
                return;
            }
            long version = activity.getVersion() == null ? 0L : activity.getVersion();
            String statusValue = (activity.getStatus() == null ? "" : activity.getStatus())
                    + "|" + version;
            redis.execute(STATUS_CAS, java.util.List.of(STATUS_KEY_PREFIX + activityNo),
                    statusValue, String.valueOf(version));
            String gray = (activity.getGrayPercent() == null ? GRAY_NO_RULE
                    : String.valueOf(activity.getGrayPercent()))
                    + "|"
                    + (activity.getGrayWhitelist() == null ? "" : activity.getGrayWhitelist());
            redis.opsForValue().set(GRAY_KEY_PREFIX + activityNo, gray);
        } catch (RuntimeException e) {
            log.warn("[activity-gate] 即时发布失败（DB 已提交，等下轮全量重写收敛）activityNo={}: {}",
                    activityNo, e.toString());
        }
    }
}
