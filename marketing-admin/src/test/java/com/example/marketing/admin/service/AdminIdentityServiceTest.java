package com.example.marketing.admin.service;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.security.AdminClaims;
import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.common.security.AdminTokenCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H1 回归（2026-09-29 架构审查）：admin 进程的身份证只认验签 token。
 *
 * <p>曾经存在"四个 X-Admin-* 身份头齐全即免验签"的快路径，它对经网关的请求是安全的
 * （网关先删后写这些头），但对直连 8085/8086 的请求等于"同网段谁都能当管理员"。
 * 本用例把这条边界钉死：裸头（无论多齐全）一律 40100；带有效签名才放行，
 * 且身份字段一律取 claims，不取可被直连者随意填充的头。</p>
 */
class AdminIdentityServiceTest {

    private final AdminTokenCodec codec = new AdminTokenCodec("unit-test-secret", Duration.ofSeconds(30));
    private final AdminSessionService sessions = mock(AdminSessionService.class);
    private final AdminIdentityService identity = new AdminIdentityService(codec, sessions);

    private AdminClaims claims() {
        Instant now = Instant.now();
        return new AdminClaims(1L, "admin", "admin", 0, "jti-1",
                now.getEpochSecond() - 10, now.getEpochSecond() + 900);
    }

    private MockHttpServletRequest bareAdminHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // 攻击者自报的全套身份：角色 admin、uid 随便填
        request.addHeader("X-Admin-User", "admin");
        request.addHeader("X-Admin-Role", "admin");
        request.addHeader("X-Admin-Jti", "jti-1");
        request.addHeader("X-Admin-Uid", "1");
        return request;
    }

    @Test
    @DisplayName("H1：四个身份头再齐全，没有签名 token 就是一律 40100")
    void bareHeadersAreNotCredentials() {
        when(sessions.isRevoked(anyString())).thenReturn(false);
        when(sessions.bumpedAt(anyLong())).thenReturn(null);

        MockHttpServletRequest request = bareAdminHeaders();

        BizException e = assertThrows(BizException.class, () -> identity.resolve(request));
        assertEquals(40100, e.getCode());
    }

    @Test
    @DisplayName("网关路径：X-Admin-Token 验签通过，身份取 claims 而非同名头")
    void relayedTokenWinsOverHeaders() {
        when(sessions.isRevoked(anyString())).thenReturn(false);
        when(sessions.bumpedAt(anyLong())).thenReturn(null);
        // 头里自报 uid=2，claims 里是 uid=1：必须以 claims 为准
        MockHttpServletRequest request = bareAdminHeaders();
        request.addHeader("X-Admin-Uid", "2");
        request.addHeader("X-Admin-Token", codec.issue(claims()));

        AdminPrincipal principal = identity.resolve(request);

        assertEquals(1L, principal.uid());
        assertEquals("admin", principal.username());
        assertEquals("jti-1", principal.jti());
    }

    @Test
    @DisplayName("直连路径：Authorization Bearer 照旧可用（本机调试/运维的回退）")
    void authorizationBearerStillWorks() {
        when(sessions.isRevoked(anyString())).thenReturn(false);
        when(sessions.bumpedAt(anyLong())).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + codec.issue(claims()));

        AdminPrincipal principal = identity.resolve(request);

        assertEquals(1L, principal.uid());
    }

    @Test
    @DisplayName("伪造的 X-Admin-Token 签名不过 → 40100，而不是退回去信裸头")
    void forgedRelayedTokenRejected() {
        MockHttpServletRequest request = bareAdminHeaders();
        request.addHeader("X-Admin-Token", "not-a-signed-token");

        BizException e = assertThrows(BizException.class, () -> identity.resolve(request));
        assertEquals(40100, e.getCode());
    }

    @Test
    @DisplayName("已吊销会话：网关透传的 token 也要过 admin 侧吊销位")
    void revokedSessionRejectedEvenViaGateway() {
        when(sessions.isRevoked("jti-1")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Admin-Token", codec.issue(claims()));

        BizException e = assertThrows(BizException.class, () -> identity.resolve(request));
        assertEquals(ErrorCode.SESSION_REVOKED.getCode(), e.getCode());
    }
}
