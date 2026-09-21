package com.example.marketing.discount.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.api.Result;
import com.example.marketing.common.util.JsonUtils;
import com.example.marketing.discount.domain.CalcInput;
import com.example.marketing.discount.domain.CalcResult;
import com.example.marketing.discount.domain.PromoRuleDsl;
import com.example.marketing.discount.dto.RuleSaveRequest;
import com.example.marketing.discount.infrastructure.entity.PromoRuleEntity;
import com.example.marketing.discount.infrastructure.mapper.PromoRuleMapper;
import com.example.marketing.discount.service.DiscountCalcService;
import com.example.marketing.discount.service.RuleCacheManager;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 优惠引擎对外接口：结算/购物车计算 + 规则管理（保存即 bump 版本号广播失效）。
 */
@RestController
@RequestMapping("/api/discount")
@RequiredArgsConstructor
public class DiscountController {

    private final DiscountCalcService calcService;
    private final RuleCacheManager ruleCacheManager;
    private final PromoRuleMapper promoRuleMapper;

    /** 优惠计算：输入购物车，输出命中规则 + 分摊明细；超时自动降级为原价 */
    @PostMapping("/calculate")
    public Result<CalcResult> calculate(@RequestBody @Valid CalcInput input) {
        if (input.getItems() == null || input.getItems().isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "购物车不能为空");
        }
        return Result.ok(calcService.calculate(input));
    }

    /** 新建/更新规则（按 ruleNo upsert），保存后推版本号让全实例刷新快照 */
    @PostMapping("/rules")
    public Result<String> saveRule(@RequestBody @Valid RuleSaveRequest request) {
        PromoRuleDsl dsl = toDsl(request);
        String ruleJson = JsonUtils.toJson(dsl);
        PromoRuleEntity existing = promoRuleMapper.selectOne(
                new LambdaQueryWrapper<PromoRuleEntity>().eq(PromoRuleEntity::getRuleNo, request.getRuleNo()));
        if (existing == null) {
            PromoRuleEntity entity = new PromoRuleEntity();
            fill(entity, request, ruleJson);
            entity.setVersion(0);
            promoRuleMapper.insert(entity);
        } else {
            fill(existing, request, ruleJson);
            promoRuleMapper.updateById(existing);
        }
        ruleCacheManager.bumpVersion();
        return Result.ok(request.getRuleNo());
    }

    /** 规则列表（管理端） */
    @GetMapping("/rules")
    public Result<List<PromoRuleEntity>> listRules() {
        return Result.ok(promoRuleMapper.selectList(
                new LambdaQueryWrapper<PromoRuleEntity>().orderByAsc(PromoRuleEntity::getRuleNo)));
    }

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
