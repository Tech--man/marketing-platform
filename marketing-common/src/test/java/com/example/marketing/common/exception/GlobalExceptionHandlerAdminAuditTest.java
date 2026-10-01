package com.example.marketing.common.exception;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.audit.AuditOutbox;
import com.example.marketing.common.audit.AuditPayload;
import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.common.security.AdminRequestIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理写失败留痕（2026-10-01 审计 P2-10）：
 * /api/admin/** 上的非 GET 请求被 BizException 拒绝时必须投审计——此前控制器只在
 * 成功后落 audit()，"谁试图改、被什么挡下"在 admin_audit_log 里完全不可见。
 *
 * <p>变异口径：把 auditAdminRejection 的路径前缀判掉（或改成 GET 也记 / 全路径都记），
 * cEndPathsNotAudited / getRejectionNotAudited 必须红。</p>
 */
class GlobalExceptionHandlerAdminAuditTest {

    private final AuditOutbox outbox = mock(AuditOutbox.class);
    private final AdminRequestIdentity identity = mock(AdminRequestIdentity.class);

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRemoteAddr("203.0.113.7");
        return r;
    }

    @Test
    @DisplayName("POST /api/admin/** 被拒 → 留痕：resultCode=业务码，未认证记匿名")
    void adminPostRejectionAuditedAsAnonymous() {
        when(identity.require(any(), org.mockito.ArgumentMatchers.any(String[].class)))
                .thenThrow(BizException.of(ErrorCode.UNAUTHORIZED, "凭证无效"));
        GlobalExceptionHandler handler = new GlobalExceptionHandler(outbox, identity);

        handler.handleBiz(BizException.of(ErrorCode.CONFIG_VERSION_CONFLICT, "数据已被他人修改"),
                request("PUT", "/api/admin/activities/ACT1/budget"));

        org.mockito.ArgumentCaptor<AuditPayload> captured =
                org.mockito.ArgumentCaptor.forClass(AuditPayload.class);
        verify(outbox).record(captured.capture());
        assertEquals("admin.request.rejected", captured.getValue().action());
        assertEquals(41008, captured.getValue().resultCode());
        assertEquals("数据已被他人修改", captured.getValue().errorMsg());
        assertEquals("(未认证)", captured.getValue().actorName(),
                "身份解析不了（本就是被拒的请求）记匿名而不是丢掉留痕");
        assertNull(captured.getValue().actorId());
    }

    @Test
    @DisplayName("凭证有效但被业务拒（40300）→ 留痕带真实操作者")
    void adminRejectionAuditedWithResolvedActor() {
        when(identity.require(any(), org.mockito.ArgumentMatchers.any(String[].class)))
                .thenReturn(new AdminPrincipal(1L, "operator", "OPERATOR", "jti-1"));
        GlobalExceptionHandler handler = new GlobalExceptionHandler(outbox, identity);

        handler.handleBiz(BizException.of(ErrorCode.FORBIDDEN, "需要角色 ADMIN"),
                request("POST", "/api/admin/cache/reheat"));

        org.mockito.ArgumentCaptor<AuditPayload> captured =
                org.mockito.ArgumentCaptor.forClass(AuditPayload.class);
        verify(outbox).record(captured.capture());
        assertEquals("operator", captured.getValue().actorName());
        assertEquals("OPERATOR", captured.getValue().role());
        assertEquals(40300, captured.getValue().resultCode());
    }

    @Test
    @DisplayName("GET 拒绝与 /api/** 业务路径被拒 → 不留痕（审计表只收管理写）")
    void nonAdminOrGetRejectionsNotAudited() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(outbox, identity);

        handler.handleBiz(BizException.of(ErrorCode.FORBIDDEN, "需要角色 ADMIN"),
                request("GET", "/api/admin/users"));
        handler.handleBiz(BizException.of(ErrorCode.BIZ_ERROR, "超限"),
                request("POST", "/api/coupon/grant"));

        verify(outbox, never()).record(any());
    }

    @Test
    @DisplayName("未装配 AuditOutbox（如网关侧）→ 静默跳过，响应不受影响")
    void noOutboxStillAnswers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(null, null);

        var resp = handler.handleBiz(BizException.of(ErrorCode.ACTIVITY_NOT_ONLINE, "未上线"),
                request("POST", "/api/admin/activities/ACT1/transition"));
        assertEquals(41007, resp.getBody().getCode());
    }
}
