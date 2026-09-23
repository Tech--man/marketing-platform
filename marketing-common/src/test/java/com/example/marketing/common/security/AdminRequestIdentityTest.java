package com.example.marketing.common.security;

import com.example.marketing.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务服务侧的后台身份。
 *
 * <p>本文件最值钱的一条是 {@link #bareHeadersAreNotCredentials}：③ 之前母版 §6.0 说
 * "业务侧读 X-Admin-* 头就够了，网关先删后写所以伪造不到"。那句话只对<b>经网关</b>的请求成立，
 * 而 FULL 进程形态业务端口监听 {@code *:808x}、LITE 的 standalone 8085 按设计发布到宿主机 ——
 * 裸头等于局域网里谁都能改预算。将来若有人"为了省事"退回读头，这条必须红。</p>
 */
class AdminRequestIdentityTest {

    private static final String SECRET = "unit-test-secret";
    /**
     * 用真实时钟而不是写死一个 epoch：{@code AdminTokenCodec.verify} 只比 {@code now > exp}，
     * 写死的"过去时刻"会随日历漂移成未来时刻，那条过期断言于是变成永真/永假的摆设。
     */
    private static long now() {
        return java.time.Instant.now().getEpochSecond();
    }

    private final AdminTokenCodec codec = new AdminTokenCodec(SECRET, Duration.ofSeconds(30));
    private final AdminRequestIdentity identity = new AdminRequestIdentity(SECRET, Duration.ofSeconds(30));

    /** 字段顺序即 AdminClaims 的构造顺序：uid, sub, role, pwdVersion, jti, iat, exp */
    private String token(long uid, String sub, String role, long iat) {
        return codec.issue(new AdminClaims(uid, sub, role, 1, "jti-" + uid, iat, iat + 3600));
    }

    private MockHttpServletRequest with(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (token != null) {
            req.addHeader(AdminRequestIdentity.TOKEN_HEADER, token);
        }
        return req;
    }

    @Test
    @DisplayName("裸身份头不构成授权：只有 X-Admin-* 而没有签名 token 时 40100")
    void bareHeadersAreNotCredentials() {
        MockHttpServletRequest req = with(null);
        req.addHeader("X-Admin-Uid", "1");
        req.addHeader("X-Admin-User", "admin");
        req.addHeader("X-Admin-Role", AdminRoles.ADMIN);
        req.addHeader("X-Admin-Jti", "jti-1");
        BizException e = assertThrows(BizException.class, () -> identity.require(req, AdminRoles.ADMIN));
        assertEquals(40100, e.getCode(), "缺凭证是 40100，不是 40300（身份都不成立，谈不上权限）");
    }

    @Test
    @DisplayName("验签通过后身份取自 claims：头里的 role 与签名不一致时以签名为准")
    void roleComesFromClaimsNotHeaders() {
        MockHttpServletRequest req = with(token(7L, "operator", AdminRoles.OPERATOR, now()));
        req.addHeader("X-Admin-Role", AdminRoles.ADMIN);
        AdminPrincipal p = identity.require(req);
        assertEquals(7L, p.uid());
        assertEquals("operator", p.role(), "头里的 role 一律不采信");
        assertEquals(AdminRoles.OPERATOR, p.role());
    }

    @Test
    @DisplayName("角色不够是 40300：身份合法、权限不足（与 40100 分开，客户端动作不同）")
    void insufficientRoleIs40300() {
        MockHttpServletRequest req = with(token(8L, "viewer", AdminRoles.READ_ONLY, now()));
        BizException e = assertThrows(BizException.class,
                () -> identity.require(req, AdminRoles.ADMIN, AdminRoles.OPERATOR));
        assertEquals(40300, e.getCode());
        assertTrue(e.getMessage().contains("admin"), "报错要点名需要什么角色: " + e.getMessage());
    }

    @Test
    @DisplayName("过期报 40101、签错报 40100：都拒，但客户端一个静默重登一个喊无权限")
    void expiredAndBadSignatureRejectedWithDifferentCodes() {
        long stale = now() - 7200;   // exp = stale+3600 仍在过去
        BizException expired = assertThrows(BizException.class,
                () -> identity.require(with(token(9L, "admin", AdminRoles.ADMIN, stale))));
        assertEquals(40101, expired.getCode(), "过期要报 40101，不能混进 40100");

        String valid = token(9L, "admin", AdminRoles.ADMIN, now());
        MockHttpServletRequest forged = with(valid.substring(0, valid.length() - 2) + "xx");
        BizException e = assertThrows(BizException.class, () -> identity.require(forged));
        assertEquals(40100, e.getCode());
    }

    @Test
    @DisplayName("换一个密钥签的 token 必须验不过：否则谁签一枚都能改预算")
    void tokenFromAnotherSecretRejected() {
        String foreign = new AdminTokenCodec("another-secret", Duration.ofSeconds(30))
                .issue(new AdminClaims(1L, "admin", AdminRoles.ADMIN, 1, "jti-1", now(), now() + 3600));
        BizException e = assertThrows(BizException.class, () -> identity.require(with(foreign)));
        assertEquals(40100, e.getCode());
    }

    @Test
    @DisplayName("密钥为空时构造即失败：宁可不服务，也不接受一枚谁都能伪造的身份")
    void blankSecretFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AdminRequestIdentity("   ", Duration.ofSeconds(30)));
        assertTrue(e.getMessage().contains("ADMIN_JWT_SECRET"), e.getMessage());
    }
}
