package com.example.marketing.discount.controller;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.audit.AuditOutbox;
import com.example.marketing.common.audit.AuditPayload;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.common.security.AdminRequestIdentity;
import com.example.marketing.common.security.AdminRoles;
import com.example.marketing.discount.domain.RuleType;
import com.example.marketing.discount.dto.RuleSaveRequest;
import com.example.marketing.discount.dto.RuleView;
import com.example.marketing.discount.service.RuleAdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规则管理端点的角色细筛与审计。
 *
 * <p>{@code operator} 在网关是"可写运维"的角色，但规则是业务配置：
 * 这条界线与 ⑤ 的阈值端点同形，写在测试里是因为它最容易被"顺手放开"。</p>
 */
class DiscountAdminControllerTest {

    private final RuleAdminService ruleAdminService = mock(RuleAdminService.class);
    private final AdminRequestIdentity identity = mock(AdminRequestIdentity.class);
    private final AuditOutbox outbox = mock(AuditOutbox.class);
    private final DiscountAdminController controller =
            new DiscountAdminController(ruleAdminService, identity, outbox);

    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private RuleSaveRequest body() {
        RuleSaveRequest r = new RuleSaveRequest();
        r.setRuleNo("PR9001");
        r.setName("满200减30");
        r.setActivityNo("ACT2026001");
        r.setType(RuleType.FULL_REDUCTION);
        r.setThreshold(new BigDecimal("200.00"));
        r.setDiscountValue(new BigDecimal("30.00"));
        r.setPriority(10);
        r.setStatus("ENABLED");
        return r;
    }

    private RuleView view(String name, int priority, int version) {
        return new RuleView("PR9001", name, "ACT2026001", "FULL_REDUCTION", null,
                priority, "ENABLED", version, "{\"ruleNo\":\"PR9001\"}");
    }

    @Test
    @DisplayName("operator 改规则被拒（40300）：编辑规则只有 admin")
    void operatorCannotEditRules() {
        when(identity.require(any(), eq(AdminRoles.ADMIN))).thenThrow(
                BizException.of(ErrorCode.FORBIDDEN, "需要角色 admin，当前 operator"));

        BizException e = assertThrows(BizException.class,
                () -> controller.save(body(), 2, request));

        assertEquals(40300, e.getCode());
        verify(ruleAdminService, never()).save(any(), any());
        verify(outbox, never()).record(any());
    }

    @Test
    @DisplayName("编辑已有规则：动作是 update，摘要里同时有 from 与 to")
    void updateAuditsBeforeAndAfter() {
        when(identity.require(any(), eq(AdminRoles.ADMIN)))
                .thenReturn(new AdminPrincipal(1L, "admin", AdminRoles.ADMIN, "jti-1"));
        when(ruleAdminService.findView("PR9001")).thenReturn(view("旧名字", 10, 2));
        when(ruleAdminService.save(any(RuleSaveRequest.class), eq(2))).thenReturn(view("满200减30", 20, 3));

        controller.save(body(), 2, request);

        ArgumentCaptor<AuditPayload> captor = ArgumentCaptor.forClass(AuditPayload.class);
        verify(outbox).record(captor.capture());
        AuditPayload p = captor.getValue();
        assertEquals("discount.rule.update", p.action());
        assertEquals("PR9001", p.resourceId());
        assertEquals(true, p.requestSummary().contains("from={name=旧名字"), p.requestSummary());
        assertEquals(true, p.requestSummary().contains("priority=20"), p.requestSummary());
        assertEquals(true, p.requestSummary().contains("version=3"), p.requestSummary());
    }

    @Test
    @DisplayName("新建时 from 为空，动作是 create")
    void createAuditsWithoutFrom() {
        when(identity.require(any(), eq(AdminRoles.ADMIN)))
                .thenReturn(new AdminPrincipal(1L, "admin", AdminRoles.ADMIN, "jti-1"));
        when(ruleAdminService.findView("PR9001")).thenReturn(null);
        when(ruleAdminService.save(any(RuleSaveRequest.class), eq(null))).thenReturn(view("满200减30", 10, 0));

        controller.save(body(), null, request);

        ArgumentCaptor<AuditPayload> captor = ArgumentCaptor.forClass(AuditPayload.class);
        verify(outbox).record(captor.capture());
        assertEquals("discount.rule.create", captor.getValue().action());
        assertEquals(true, captor.getValue().requestSummary().contains("from=, to="),
                captor.getValue().requestSummary());
    }

    @Test
    @DisplayName("规则列表三种角色都可读，且不投审计")
    void listReadableByAllRolesAndNotAudited() {
        when(identity.require(any(), eq(AdminRoles.ADMIN), eq(AdminRoles.OPERATOR), eq(AdminRoles.READ_ONLY)))
                .thenReturn(new AdminPrincipal(4L, "viewer", AdminRoles.READ_ONLY, "jti-4"));
        when(ruleAdminService.list(any(PageQuery.class), any()))
                .thenReturn(PageResult.of(1, 1, 20, List.of(view("满200减30", 10, 1))));

        controller.list(1, 20, null, request);

        verify(outbox, never()).record(any());
    }
}
