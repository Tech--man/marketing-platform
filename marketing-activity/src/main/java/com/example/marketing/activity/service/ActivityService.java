package com.example.marketing.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.marketing.activity.dto.ActivityView;
import com.example.marketing.activity.domain.ActivityEntityConverter;
import com.example.marketing.activity.domain.ActivityEvent;
import com.example.marketing.activity.domain.ActivityStateMachine;
import com.example.marketing.activity.domain.ActivityStatus;
import com.example.marketing.activity.dto.CreateActivityRequest;
import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import com.example.marketing.activity.infrastructure.mapper.ActivityMapper;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.exception.VersionGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 活动生命周期管理：创建、状态机流转（含乐观锁）、上线时预算预热。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityService {

    private final ActivityMapper activityMapper;
    private final BudgetService budgetService;
    private final ActivityGatePublisher gatePublisher;

    public ActivityEntity create(CreateActivityRequest request) {
        // W2.4（2026-09-30 第二轮复审）：时间窗交叉校验——endTime 可空=不限，
        // 但两个都给了就必须 start<end，否则到期 Job 与展示口径全乱了
        if (request.startTime() != null && request.endTime() != null
                && !request.startTime().isBefore(request.endTime())) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "开始时间必须早于结束时间（start=" + request.startTime() + ", end=" + request.endTime() + "）");
        }
        try {
            if (activityMapper.exists(Wrappers.<ActivityEntity>lambdaQuery()
                    .eq(ActivityEntity::getActivityNo, request.activityNo()))) {
                throw new BizException(ErrorCode.BIZ_ERROR, "活动编号已存在: " + request.activityNo());
            }
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // check-then-insert 的并发窗口：同名同时创建时后插者撞唯一键，
            // 报 41000 而不是 50000"系统繁忙"（误导排障方向）
            throw new BizException(ErrorCode.BIZ_ERROR, "活动编号已存在: " + request.activityNo());
        }
        ActivityEntity entity = ActivityEntityConverter.fromRequest(request);
        activityMapper.insert(entity);
        log.info("[activity] 创建草稿活动 {}", entity.getActivityNo());
        return entity;
    }

    /**
     * 状态机流转。乐观锁冲突抛业务异常由调用方重试。
     */
    @Transactional(rollbackFor = Exception.class)
    public ActivityEntity transition(String activityNo, ActivityEvent event) {
        ActivityEntity entity = getByNo(activityNo);
        ActivityStatus current = ActivityStatus.valueOf(entity.getStatus());
        ActivityStatus target = ActivityStateMachine.next(current, event);

        entity.setStatus(target.name());
        flushWithVersion(entity);
        // 上线动作的副作用：预算预热。首次上线（无流水）全额==对账值安全；
        // 再上线（RE_ONLINE，已有消耗流水）必须按对账公式而不是全额——
        // 地雷 E 的残留（2026-09-29 审查收口）：下线期间丢键再上线，全额会把
        // 已消耗的预算凭空回涨。warmIfAbsent 不覆盖已存在的键，所以这里直接
        // 传对账值：键在（值正确）无感，键丢则按权威值重建。
        if (target == ActivityStatus.ONLINE) {
            budgetService.warmCentsIfAbsent(activityNo, budgetService.computeRemainCents(activityNo));
        }
        // 下线/暂停的生效不该等下一轮回源（那是最长 5s 的"已下线还在发券"窗口），
        // 流转后即时发布参与闸门；发布失败由发布器的周期全量重写兜底
        gatePublisher.publishNow(activityNo);
        log.info("[activity] {} 状态流转 {} -[{}]-> {}", activityNo, current, event, target);
        return entity;
    }

    /**
     * 后台改总预算。<b>必须在同一事务里 force 重预热预算键</b>：上线预热走的是 SETNX
     * （{@code warmIfAbsent}），键已存在时改 DB 不动键，于是"改了预算但 C 端余额还是旧的"——
     * 本仓库踩过一次的地雷 A。少写下面那一行，{@code ActivityAdminWriteTest} 就红。
     *
     * @param expectedVersion 列表里带回去的 version；与库里不一致说明有人先改了 → 41008
     */
    @Transactional(rollbackFor = Exception.class)
    public ActivityEntity updateBudget(String activityNo, BigDecimal budgetAmount, Integer expectedVersion) {
        ActivityEntity entity = getByNo(activityNo);
        requireVersion(entity, expectedVersion);
        BigDecimal before = entity.getBudgetAmount();
        entity.setBudgetAmount(budgetAmount);
        flushWithVersion(entity);
        budgetService.reheat(activityNo, true);
        log.info("[activity] 预算变更 {} {} -> {}", activityNo, before, budgetAmount);
        return entity;
    }

    /**
     * 后台改灰度。<b>刻意不 reheat</b>：灰度真值就是这两列，owning 侧 {@code GrayRuleCache}
     * 每 5s 回源 DB 重建（⑤ 段内 spec §3 偏离 #3），所以写库即完整语义。
     * 在这里加一次"顺手刷缓存"会让人以为不刷就不生效 —— 那是把两条路都留着的老毛病。
     */
    @Transactional(rollbackFor = Exception.class)
    public ActivityEntity updateGray(String activityNo, Integer grayPercent, String grayWhitelist,
                                     Integer expectedVersion) {
        if (grayPercent != null && (grayPercent < 0 || grayPercent > 100)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "灰度百分比必须在 0-100，当前 " + grayPercent);
        }
        ActivityEntity entity = getByNo(activityNo);
        requireVersion(entity, expectedVersion);
        Integer from = entity.getGrayPercent();
        entity.setGrayPercent(grayPercent);
        entity.setGrayWhitelist(normalizeWhitelist(grayWhitelist));
        flushWithVersion(entity);
        gatePublisher.publishNow(activityNo);
        log.info("[activity] 灰度变更 {} percent {} -> {} whitelist={}",
                activityNo, from, grayPercent, entity.getGrayWhitelist());
        return entity;
    }

    /**
     * 白名单规范化（P1，2026-09-30 第二轮复审）：逐项 trim、丢空项，重组成紧凑 CSV。
     * owning 侧 GrayRuleCache 对坏项是容错跳过，但镜像消费侧（coupon ActivityGate）
     * 曾经逐字 parseLong——"70001, 70002"（带空格）足以让一个活动的领券全 500。
     * 与其要求所有消费侧都容错，不如在唯一写入口把数据收干净：落库值恒为
     * {@code 70001,70002} 形状，两侧解析歧义从根上消除。null 原样透传（清空语义）。
     */
    private static String normalizeWhitelist(String csv) {
        if (csv == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(trimmed);
        }
        return out.toString();
    }

    /** 后台列表：状态可选过滤，按 id 倒序（新活动在前） */
    public PageResult<ActivityView> list(PageQuery query, String status) {
        Page<ActivityEntity> page = activityMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()),
                Wrappers.<ActivityEntity>lambdaQuery()
                        .eq(status != null && !status.isBlank(), ActivityEntity::getStatus, status)
                        .orderByDesc(ActivityEntity::getId));
        return PageResult.of(page.getTotal(), query.getPage(), query.getSize(),
                page.getRecords().stream().map(ActivityView::from).toList());
    }

    /**
     * 乐观锁的两道关口都在这：先比客户端看到的 version（防"我看的是三秒前的世界"），
     * 再看 {@code updateById} 的影响行数（防两次请求同时在飞、比对之后被人插队）。
     * 两件事同码 <b>41008</b>，与 {@code transition} 的并发冲突一致 ——
     * 41000 继续只表示业务失败（⑤ T9 立的码表纪律）。
     */
    private void requireVersion(ActivityEntity entity, Integer expectedVersion) {
        VersionGuard.requireEqual(expectedVersion, entity.getVersion(), "活动");
    }

    private void flushWithVersion(ActivityEntity entity) {
        if (activityMapper.updateById(entity) == 0) {
            throw VersionGuard.conflict("活动");
        }
    }

    public ActivityEntity getByNo(String activityNo) {
        ActivityEntity entity = activityMapper.selectOne(Wrappers.<ActivityEntity>lambdaQuery()
                .eq(ActivityEntity::getActivityNo, activityNo));
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "活动不存在: " + activityNo);
        }
        return entity;
    }

    /** 活动是否可参与（供券/秒杀/优惠等下游校验） */
    public boolean participatable(String activityNo) {
        ActivityEntity entity = getByNo(activityNo);
        return ActivityStatus.valueOf(entity.getStatus()).participatable();
    }
}
