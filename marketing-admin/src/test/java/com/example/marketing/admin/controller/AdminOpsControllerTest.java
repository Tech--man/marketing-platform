package com.example.marketing.admin.controller;

import com.example.marketing.admin.observe.OpsSnapshotService;
import com.example.marketing.admin.observe.OpsSnapshotView;
import com.example.marketing.admin.service.AdminIdentityService;
import com.example.marketing.common.api.Result;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 只读面的权限面：④ 的整张盘来自别人的进程与 Redis，但读它不该要写权限。
 */
class AdminOpsControllerTest {

    private final OpsSnapshotService service = mock(OpsSnapshotService.class);
    private final AdminIdentityService identity = mock(AdminIdentityService.class);
    private final AdminOpsController controller = new AdminOpsController(service, identity);

    private OpsSnapshotView view() {
        return new OpsSnapshotView("proxy", "FULL", Instant.now(), List.of(),
                new OpsSnapshotView.BacklogView(List.of(), List.of(), 0L), List.of(), List.of(),
                new OpsSnapshotView.LivenessView(java.util.Map.of(), List.of()),
                new OpsSnapshotView.AuditTableView(0L, null, List.of(), null), List.of());
    }

    @Test
    @DisplayName("任何已登录角色可读（不带角色参数 = read-only 也在内）")
    void anyRoleCanRead() {
        OpsSnapshotView snapshot = view();
        when(service.snapshot()).thenReturn(snapshot);

        Result<OpsSnapshotView> result = controller.snapshot(new MockHttpServletRequest());

        assertSame(snapshot, result.getData());
        assertEquals(0, result.getCode());
        // 关键在 require 的可变参数是空的：写了 OPERATIONAL 就会把 viewer 挡在外面
        verify(identity).require(any(MockHttpServletRequest.class));
    }

    @Test
    @DisplayName("无凭证一样进不去：只读面也在 /api/admin/** 的鉴权里面")
    void unauthenticatedRejected() {
        when(identity.require(any(MockHttpServletRequest.class)))
                .thenThrow(BizException.of(ErrorCode.UNAUTHORIZED, "缺少后台凭证"));

        BizException e = assertThrows(BizException.class,
                () -> controller.snapshot(new MockHttpServletRequest()));
        assertEquals(40100, e.getCode());
    }
}
