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
    @DisplayName("列表视图带 version：编辑时要拿它当乐观锁期望值")
    void viewCarriesVersion() {
        PromoRuleEntity e = existing("PR9001", 7);
        e.setRuleJson("{\"ruleNo\":\"PR9001\"}");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(e);

        assertEquals(Integer.valueOf(7), RuleView.from(e).version());
    }
}
