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
            ruleCacheManager.bumpVersion();
            log.info("[discount] 规则新建 {} version={}", entity.getRuleNo(), entity.getVersion());
            return RuleView.from(entity);
        }
        requireVersion(existing, expectedVersion);
        String before = existing.getName();
        fill(existing, request, ruleJson);
        if (promoRuleMapper.updateById(existing) == 0) {
            throw VersionGuard.conflict("规则");
        }
        ruleCacheManager.bumpVersion();
        log.info("[discount] 规则更新 {}（原 name={}）", existing.getRuleNo(), before);
        return RuleView.from(existing);
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
