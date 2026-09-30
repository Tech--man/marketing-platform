package com.example.marketing.discount.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.exception.VersionGuard;
import com.example.marketing.common.util.JsonUtils;
import com.example.marketing.discount.domain.PromoRuleDsl;
import com.example.marketing.discount.dto.RuleSaveRequest;
import com.example.marketing.discount.dto.RuleView;
import com.example.marketing.discount.engine.RuleSnapshot;
import com.example.marketing.discount.infrastructure.entity.PromoRuleEntity;
import com.example.marketing.discount.infrastructure.mapper.PromoRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 规则的后台写路径（③）。原先这段逻辑在 {@code DiscountController} 里：
 * 没有事务、没有期望版本，两个 operator 同时改一条规则后者会静默覆盖前者。
 *
 * <p>缓存失效走 {@link RuleCacheManager#bumpVersion()} 而不是 {@code CacheReheater}：
 * 读路径本来就是"比对 Redis 版本号才重建"，再挂一个 reheat 入口等于两套真相。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleAdminService {

    private final PromoRuleMapper promoRuleMapper;
    private final RuleCacheManager ruleCacheManager;

    /**
     * 规则 upsert + 版本号推进，<b>同一事务</b>。顺序不能反：先推版本再写库的话，
     * 全实例会立刻去重建一份还没改好的规则（回源 DB 会自愈，但窗口里算错价）。
     *
     * @param expectedVersion null 或 0 = 新建；非 0 = 编辑且必须与库里一致，否则 41008
     */
    @Transactional(rollbackFor = Exception.class)
    public RuleView save(RuleSaveRequest request, Integer expectedVersion) {
        // H9（2026-09-29 架构审查）：user: 前缀的人群规则暂不可创建。
        // 引擎的匹配语义是"规则带 user:X，输入侧 userTags 含 X 即命中"，而 C 端入口
        // 已把自报 userTags 覆写为空集（DiscountController）——这类规则今天不存在
        // 能被正当满足的路径，允许创建等于放一条"永远命中不了"的死规则进快照；
        // 将来接上可信人群服务、入口恢复按 userId 填标签时，把这道闸拆掉。
        if (request.getRequiredTags() != null) {
            request.getRequiredTags().stream()
                    .filter(tag -> tag != null && tag.startsWith(RuleSnapshot.USER_TAG_PREFIX))
                    .findAny()
                    .ifPresent(tag -> {
                        throw BizException.of(ErrorCode.BAD_REQUEST,
                                "人群标签规则（" + tag + "…）暂不可创建：服务端尚无可信人群来源，"
                                        + "等人群服务接入后再启用");
                    });
        }
        validateValueRanges(request);
        PromoRuleEntity existing = byRuleNo(request.getRuleNo());
        String ruleJson = JsonUtils.toJson(toDsl(request));
        if (existing == null) {
            if (expectedVersion != null && expectedVersion != 0) {
                // 带非 0 版本的新建只可能是"我以为它存在"——误写 ruleNo 会静默造出一条规则
                throw BizException.of(ErrorCode.CONFIG_VERSION_CONFLICT,
                        "规则 " + request.getRuleNo() + " 不存在，新建不能带 version=" + expectedVersion);
            }
            PromoRuleEntity entity = new PromoRuleEntity();
            fill(entity, request, ruleJson);
            entity.setVersion(0);
            promoRuleMapper.insert(entity);
            bumpAfterCommit();
            log.info("[discount] 规则新建 {} version={}", entity.getRuleNo(), entity.getVersion());
            return RuleView.from(entity);
        }
        requireVersion(existing, expectedVersion);
        String before = existing.getName();
        fill(existing, request, ruleJson);
        if (promoRuleMapper.updateById(existing) == 0) {
            throw VersionGuard.conflict("规则");
        }
        bumpAfterCommit();
        log.info("[discount] 规则更新 {}（原 name={}）", existing.getRuleNo(), before);
        return RuleView.from(existing);
    }

    /**
     * 值域防呆（2026-09-29 审查收口）：管理面有 token+审计+乐观锁，但数值本身
     * 没有任何闸——rate 填 0 等于全场免费、discountValue 填成天文数字、ladder
     * 乱序时"最高档"算错，全部直接全量错价。输入侧拒绝比引擎里事后夹取便宜一百倍。
     */
    private void validateValueRanges(RuleSaveRequest request) {
        if (request.getDiscountRate() != null
                && (request.getDiscountRate().signum() <= 0
                    || request.getDiscountRate().doubleValue() > 10)) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "折扣率 discountRate 必须在 (0, 10]（8.5 = 八五折），当前 " + request.getDiscountRate());
        }
        if (request.getDiscountValue() != null && request.getDiscountValue().signum() < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "立减额 discountValue 不能为负: " + request.getDiscountValue());
        }
        if (request.getThreshold() != null && request.getThreshold().signum() < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "门槛 threshold 不能为负: " + request.getThreshold());
        }
        if (request.getLadderSteps() != null && !request.getLadderSteps().isEmpty()) {
            java.util.List<com.example.marketing.discount.domain.PromoRuleDsl.LadderStep> steps =
                    new java.util.ArrayList<>(request.getLadderSteps());
            steps.sort(java.util.Comparator.comparing(
                    s -> s.getThreshold() == null ? java.math.BigDecimal.ZERO : s.getThreshold()));
            for (int i = 0; i < steps.size(); i++) {
                com.example.marketing.discount.domain.PromoRuleDsl.LadderStep step = steps.get(i);
                if (step.getThreshold() == null || step.getDiscountValue() == null
                        || step.getThreshold().signum() < 0 || step.getDiscountValue().signum() < 0) {
                    throw BizException.of(ErrorCode.BAD_REQUEST, "阶梯档的门槛/立减额都不能为空或负");
                }
                if (i > 0 && step.getThreshold().compareTo(steps.get(i - 1).getThreshold()) <= 0) {
                    throw BizException.of(ErrorCode.BAD_REQUEST, "阶梯档门槛必须互不相同且升序排列");
                }
                if (step.getDiscountValue().compareTo(step.getThreshold()) > 0) {
                    throw BizException.of(ErrorCode.BAD_REQUEST,
                            "阶梯档立减额 " + step.getDiscountValue() + " 超过门槛 " + step.getThreshold()
                                    + "（满 300 减 400 是倒贴）");
                }
            }
        }
        // DISCOUNT 类型必须有 rate；FULL_REDUCTION/LADDER 必须有对应金额（引擎降级原价之外的另一类静默错）
        if (request.getType() == com.example.marketing.discount.domain.RuleType.DISCOUNT
                && request.getDiscountRate() == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "DISCOUNT 类型必须给 discountRate");
        }
        if (request.getType() == com.example.marketing.discount.domain.RuleType.FULL_REDUCTION
                && request.getDiscountValue() == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "FULL_REDUCTION 类型必须给 discountValue");
        }
        if (request.getType() == com.example.marketing.discount.domain.RuleType.LADDER
                && (request.getLadderSteps() == null || request.getLadderSteps().isEmpty())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "LADDER 类型必须给 ladderSteps");
        }
    }

    /**
     * H8（2026-09-29 架构审查）：bump 必须发生在事务提交之后。save 是 @Transactional，
     * 提交前推版本号的话，其他实例在"bump 已见、数据未提交"的窗口里重建，会把
     * <b>旧数据</b>装进<b>新版本号</b>的快照——之后版本比对恒相等，那份错快照永不重建。
     * 注册 afterCommit 钩子；无事务上下文（单测直调）时立即 bump。
     */
    private void bumpAfterCommit() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                    ruleCacheManager.bumpVersion();
                                }
                            });
        } else {
            ruleCacheManager.bumpVersion();
        }
    }

    /** 列表：按 ruleNo 升序，与原 {@code listRules} 同序；DSL 全文只在后台可见 */
    public PageResult<RuleView> list(PageQuery query, String status) {
        IPage<PromoRuleEntity> page = promoRuleMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()),
                new LambdaQueryWrapper<PromoRuleEntity>()
                        .eq(status != null && !status.isBlank(), PromoRuleEntity::getStatus, status)
                        .orderByAsc(PromoRuleEntity::getRuleNo));
        return PageResult.of(page.getTotal(), query.getPage(), query.getSize(),
                page.getRecords().stream().map(RuleView::from).toList());
    }

    /** 给审计取"改之前"的样子：不存在返回 null（= 这是新建） */
    public RuleView findView(String ruleNo) {
        PromoRuleEntity entity = byRuleNo(ruleNo);
        return entity == null ? null : RuleView.from(entity);
    }

    private PromoRuleEntity byRuleNo(String ruleNo) {
        return promoRuleMapper.selectOne(
                new LambdaQueryWrapper<PromoRuleEntity>().eq(PromoRuleEntity::getRuleNo, ruleNo));
    }

    private void requireVersion(PromoRuleEntity entity, Integer expectedVersion) {
        VersionGuard.requireEqual(expectedVersion, entity.getVersion(), "规则");
    }

    /** 以下两个方法从 DiscountController 原样搬来，不改逻辑：那是既有的 DSL 组装口径 */
    private PromoRuleDsl toDsl(RuleSaveRequest request) {
        PromoRuleDsl dsl = new PromoRuleDsl();
        dsl.setRuleNo(request.getRuleNo());
        dsl.setName(request.getName());
        dsl.setActivityNo(request.getActivityNo());
        dsl.setType(request.getType());
        dsl.setRequiredTags(request.getRequiredTags());
        dsl.setExcludeTags(request.getExcludeTags());
        dsl.setThreshold(request.getThreshold());
        dsl.setDiscountValue(request.getDiscountValue());
        dsl.setDiscountRate(request.getDiscountRate());
        dsl.setLadderSteps(request.getLadderSteps());
        dsl.setMutexGroup(request.getMutexGroup());
        dsl.setPriority(request.getPriority());
        dsl.setPerUserLimit(request.getPerUserLimit());
        return dsl;
    }

    private void fill(PromoRuleEntity entity, RuleSaveRequest request, String ruleJson) {
        entity.setRuleNo(request.getRuleNo());
        entity.setName(request.getName());
        entity.setActivityNo(request.getActivityNo());
        entity.setRuleType(request.getType().name());
        entity.setMutexGroup(request.getMutexGroup());
        entity.setPriority(request.getPriority());
        entity.setStatus(request.getStatus());
        entity.setRuleJson(ruleJson);
    }
}
