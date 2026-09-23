package com.example.marketing.activity.controller;

import com.example.marketing.activity.dto.ActivityView;
import com.example.marketing.activity.dto.BudgetUpdateRequest;
import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import com.example.marketing.activity.service.ActivityService;
import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.audit.AuditOutbox;
import com.example.marketing.common.audit.AuditPayload;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.common.security.AdminRequestIdentity;
import com.example.marketing.common.security.AdminRoles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端点的三件事：身份从验签来、角色细筛、写成功必须留审计。
 *
 * <p>不引 MockMvc：本仓库的 controller 测试都是直接 new controller + mock 依赖，
 * 身份与审计这两条恰好是最容易在重构时静默丢掉的（丢掉后接口照样 200）。</p>
 */
class ActivityAdminControllerTest {

    private final ActivityService activityService = mock(ActivityService.class);
    private final AdminRequestIdentity identity = mock(AdminRequestIdentity.class);
    private final AuditOutbox outbox = mock(AuditOutbox.class);
    private final ActivityAdminController controller =
            new ActivityAdminController(activityService, identity, outbox);

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final AdminPrincipal admin = new AdminPrincipal(1L, "admin", AdminRoles.ADMIN, "jti-1");
    private final AdminPrincipal viewer = new AdminPrincipal(4L, "viewer", AdminRoles.READ_ONLY, "jti-4");

    private ActivityEntity entity(String budget, int version) {
        ActivityEntity e = new ActivityEntity();
        e.setActivityNo("ACT9001");
        e.setName("冒烟活动");
        e.setStatus("ONLINE");
        e.setBudgetAmount(new BigDecimal(budget));
        e.setVersion(version);
        e.setStartTime(LocalDateTime.now());
        e.setEndTime(LocalDateTime.now().plusDays(1));
        return e;
    }

    @Test
    @DisplayName("验签不通过（identity 抛 40100）时不执行写、也不投审计")
    void unauthenticatedWritesDoNothing() {
        when(identity.require(any(), any(String[].class)))
                .thenThrow(BizException.of(com.example.marketing.common.api.ErrorCode.UNAUTHORIZED, "缺少后台凭证"));

        assertThrows(BizException.class, () -> controller.updateBudget("ACT9001",
                new BudgetUpdateRequest(new BigDecimal("80.00"), 3, null), request));

        verify(activityService, never()).updateBudget(anyString(), any(), any());
        verify(outbox, never()).record(any());
    }

    @Test
    @DisplayName("只读角色调写端点：identity 以 40300 拒，写不执行")
    void readOnlyCannotWrite() {
        when(identity.require(any(), eq(AdminRoles.ADMIN))).thenThrow(
                BizException.of(com.example.marketing.common.api.ErrorCode.FORBIDDEN, "需要角色 admin"));

        BizException e = assertThrows(BizException.class, () -> controller.updateBudget("ACT9001",
                new BudgetUpdateRequest(new BigDecimal("80.00"), 3, null), request));
        assertEquals(40300, e.getCode());
        verify(activityService, never()).updateBudget(anyString(), any(), any());
    }

    @Test
    @DisplayName("改预算成功要投一条审计，summary 里能看到 from/to 与 version")
    void successfulWriteRecordsAuditWithBeforeAfter() {
        when(identity.require(any(), eq(AdminRoles.ADMIN))).thenReturn(admin);
        when(activityService.getByNo("ACT9001")).thenReturn(entity("100.00", 3));
        when(activityService.updateBudget("ACT9001", new BigDecimal("80.00"), 3))
                .thenReturn(entity("80.00", 4));

        controller.updateBudget("ACT9001", new BudgetUpdateRequest(new BigDecimal("80.00"), 3, null), request);

        ArgumentCaptor<AuditPayload> captor = ArgumentCaptor.forClass(AuditPayload.class);
        verify(outbox).record(captor.capture());
        AuditPayload p = captor.getValue();
        assertEquals("activity.budget.set", p.action());
        assertEquals("ACT9001", p.resourceId());
        assertEquals(Long.valueOf(1L), p.actorId());
        assertEquals("admin", p.role());
        assertEquals(true, p.requestSummary().contains("from=100.00"), p.requestSummary());
        assertEquals(true, p.requestSummary().contains("to=80.00"), p.requestSummary());
        assertEquals(true, p.requestSummary().contains("version=4"), p.requestSummary());
    }

    @Test
    @DisplayName("列表对三种角色都放开，且不投审计（只读不留痕）")
    void listIsReadableAndNotAudited() {
        when(identity.require(any(), eq(AdminRoles.ADMIN), eq(AdminRoles.OPERATOR), eq(AdminRoles.READ_ONLY)))
                .thenReturn(viewer);
        when(activityService.list(any(PageQuery.class), any()))
                .thenReturn(PageResult.of(1, 1, 20, List.of(ActivityView.from(entity("100.00", 3)))));

        controller.list(1, 20, null, request);

        verify(outbox, never()).record(any());
    }
}
