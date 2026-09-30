package com.example.marketing.discount.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.marketing.discount.domain.RuleType;
import com.example.marketing.discount.dto.RuleSaveRequest;
import com.example.marketing.discount.dto.RuleView;
import com.example.marketing.discount.infrastructure.entity.PromoRuleEntity;
import com.example.marketing.discount.infrastructure.mapper.PromoRuleMapper;
import com.example.marketing.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规则写的三条硬约束：推版本号、乐观锁、**顺序**（写库在前）。
 *
 * <p>现状是这三条都没了：{@code DiscountController.saveRule} 在 controller 里做 upsert，
 * 没有事务、没有期望版本，两个 operator 同时改一条规则后者静默覆盖前者。
 * 本任务是修既有缺陷，不是加新能力。</p>
 */
class RuleAdminServiceTest {

    private final PromoRuleMapper mapper = mock(PromoRuleMapper.class);
    private final RuleCacheManager cacheManager = mock(RuleCacheManager.class);
    private final RuleAdminService service = new RuleAdminService(mapper, cacheManager);

    private RuleSaveRequest request(String ruleNo) {
        RuleSaveRequest r = new RuleSaveRequest();
        r.setRuleNo(ruleNo);
        r.setName("满200减30");
        r.setActivityNo("ACT2026001");
        r.setType(RuleType.FULL_REDUCTION);
        r.setThreshold(new BigDecimal("200.00"));
        r.setDiscountValue(new BigDecimal("30.00"));
        r.setPriority(10);
        return r;
    }

    private PromoRuleEntity existing(String ruleNo, int version) {
        PromoRuleEntity e = new PromoRuleEntity();
        e.setRuleNo(ruleNo);
        e.setName("旧名字");
        e.setVersion(version);
        return e;
    }

    @Test
    @DisplayName("规则写必须 bumpVersion：不推版本号，其他实例还在用旧快照")
    void saveBumpsCacheVersion() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing("PR9001", 2));
        when(mapper.updateById(any(PromoRuleEntity.class))).thenReturn(1);

        service.save(request("PR9001"), 2);

        verify(cacheManager).bumpVersion();
    }

    @Test
    @DisplayName("expectedVersion 与库里不一致 → 41008，既不写库也不推版本")
    void staleVersionRejected() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing("PR9001", 5));

        BizException e = assertThrows(BizException.class, () -> service.save(request("PR9001"), 2));

        assertEquals(41008, e.getCode());
        verify(mapper, never()).updateById(any(PromoRuleEntity.class));
        verify(mapper, never()).insert(any(PromoRuleEntity.class));
        verify(cacheManager, never()).bumpVersion();
    }

    @Test
    @DisplayName("真并发（@Version 已不匹配，updateById 返回 0）也不推版本号：顺序是写库 → 推版本")
    void versionNotBumpedWhenWriteFails() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing("PR9001", 2));
        when(mapper.updateById(any(PromoRuleEntity.class))).thenReturn(0);

        assertThrows(BizException.class, () -> service.save(request("PR9001"), 2));

        verify(cacheManager, never()).bumpVersion();
    }

    @Test
    @DisplayName("新建：写库后推版本号")
    void createInsertsAndBumps() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        RuleView created = service.save(request("PR9002"), null);

        assertEquals("PR9002", created.ruleNo());
        verify(mapper).insert(any(PromoRuleEntity.class));
        verify(cacheManager).bumpVersion();
    }

    @Test
    @DisplayName("误写 ruleNo 的新建（带非 0 期望版本）直接 41008，一条规则都不该被造出来")
    void createWithForeignVersionIsRejectedWithoutInsert() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.save(request("PR9003"), 3));

        assertEquals(41008, e.getCode());
        verify(mapper, never()).insert(any(PromoRuleEntity.class));
        verify(cacheManager, never()).bumpVersion();
    }

    @Test
    @DisplayName("H9：user: 前缀的人群规则拒绝保存——C 端已不收自报标签，这类规则无人能正当命中")
    void userTagRuleIsRejected() {
        RuleSaveRequest r = request("PR9004");
        r.setRequiredTags(java.util.Set.of("user:MEMBER"));

        BizException e = assertThrows(BizException.class, () -> service.save(r, null));

        assertEquals(40000, e.getCode());
        verify(mapper, never()).insert(any(PromoRuleEntity.class));
        verify(mapper, never()).updateById(any(PromoRuleEntity.class));
        verify(cacheManager, never()).bumpVersion();
    }

    @Test
    @DisplayName("商品维度标签（无 user: 前缀）不受影响")
    void itemTagRuleStillSaves() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        RuleSaveRequest r = request("PR9005");
        r.setRequiredTags(java.util.Set.of("DIGITAL"));

        service.save(r, null);

        verify(mapper).insert(any(PromoRuleEntity.class));
    }

    @Test
    @DisplayName("第六批 W1：discountRate=0 或 >10 拒绝（0 = 全场免费）")
    void zeroDiscountRateRejected() {
        RuleSaveRequest r = request("PR9006");
        r.setType(RuleType.DISCOUNT);
        r.setDiscountRate(java.math.BigDecimal.ZERO);

        BizException e = assertThrows(BizException.class, () -> service.save(r, null));
        assertEquals(40000, e.getCode());
    }

    @Test
    @DisplayName("第六批 W1：discountRate 超 10 拒绝（>10 = 负折扣 = 倒贴）")
    void overTenDiscountRateRejected() {
        RuleSaveRequest r = request("PR9007");
        r.setType(RuleType.DISCOUNT);
        r.setDiscountRate(new java.math.BigDecimal("10.5"));

        BizException e = assertThrows(BizException.class, () -> service.save(r, null));
        assertEquals(40000, e.getCode());
    }

    @Test
    @DisplayName("第六批 W1：阶梯档立减超门槛拒绝（满 300 减 400 是倒贴）")
    void ladderOverThresholdRejected() {
        RuleSaveRequest r = request("PR9008");
        r.setType(RuleType.LADDER);
        com.example.marketing.discount.domain.PromoRuleDsl.LadderStep step =
                new com.example.marketing.discount.domain.PromoRuleDsl.LadderStep();
        step.setThreshold(new java.math.BigDecimal("300"));
        step.setDiscountValue(new java.math.BigDecimal("400"));
        r.setLadderSteps(java.util.List.of(step));

        BizException e = assertThrows(BizException.class, () -> service.save(r, null));
        assertEquals(40000, e.getCode());
    }

    @Test
    @DisplayName("P1：requiredTags 含 null/空白元素直接拒（引擎快照构建裸 startsWith 会 NPE）")
    void nullTagElementRejected() {
        RuleSaveRequest r = request("PR9009");
        r.setRequiredTags(java.util.Set.of());

        java.util.Set<String> withNull = new java.util.HashSet<>();
        withNull.add("DIGITAL");
        withNull.add(null);
        r.setRequiredTags(withNull);

        BizException e = assertThrows(BizException.class, () -> service.save(r, null));
        assertEquals(40000, e.getCode());
    }

    @Test
    @DisplayName("P1：乱序阶梯档按门槛升序落库（引擎 bestLadder 是存储顺序最后满足者胜）")
    void outOfOrderLadderSortedOnPersist() {
        RuleSaveRequest r = request("PR9010");
        r.setType(RuleType.LADDER);
        com.example.marketing.discount.domain.PromoRuleDsl.LadderStep high =
                new com.example.marketing.discount.domain.PromoRuleDsl.LadderStep();
        high.setThreshold(new java.math.BigDecimal("300"));
        high.setDiscountValue(new java.math.BigDecimal("50"));
        com.example.marketing.discount.domain.PromoRuleDsl.LadderStep low =
                new com.example.marketing.discount.domain.PromoRuleDsl.LadderStep();
        low.setThreshold(new java.math.BigDecimal("200"));
        low.setDiscountValue(new java.math.BigDecimal("20"));
        // 故意乱序提交：校验段排副本查重放行，落库若存原序，金额 350 时 200 档会覆盖 300 档
        r.setLadderSteps(java.util.List.of(high, low));

        org.mockito.Mockito.when(mapper.insert(any(PromoRuleEntity.class))).thenReturn(1);
        service.save(r, null);

        org.mockito.ArgumentCaptor<PromoRuleEntity> captor =
                org.mockito.ArgumentCaptor.forClass(PromoRuleEntity.class);
        org.mockito.Mockito.verify(mapper).insert(captor.capture());
        com.example.marketing.discount.domain.PromoRuleDsl dsl =
                com.example.marketing.common.util.JsonUtils.parse(
                        captor.getValue().getRuleJson(), com.example.marketing.discount.domain.PromoRuleDsl.class);
        java.math.BigDecimal first = dsl.getLadderSteps().get(0).getThreshold();
        java.math.BigDecimal second = dsl.getLadderSteps().get(1).getThreshold();
        org.junit.jupiter.api.Assertions.assertTrue(
                first.compareTo(second) < 0,
                "落库必须升序（first=" + first + ", second=" + second + "）——存原序则引擎取错档");
    }

    @Test
    @DisplayName("列表视图带 version：编辑时要拿它当乐观锁期望值")
    void viewCarriesVersion() {
        PromoRuleEntity e = existing("PR9001", 7);
        e.setRuleJson("{\"ruleNo\":\"PR9001\"}");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(e);

        assertEquals(Integer.valueOf(7), RuleView.from(e).version());
    }
}
