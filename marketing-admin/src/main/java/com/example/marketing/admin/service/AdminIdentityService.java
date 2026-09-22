package com.example.marketing.admin.service;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.security.AdminClaims;
import com.example.marketing.common.security.AdminTokenCodec;
import com.example.marketing.common.security.TokenVerifyResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;

/**
 * "当前这个请求是谁"的唯一出处。
 *
 * <p>网关（{@code AdminAuthFilter}）已经验过一次签名，这里为什么还要验：
 * 后台最敏感的两个操作（登出、改密）要拿到 jti 才能作废会话，而 jti 只能从签名里来 ——
 * 相信请求头等于相信任何能直连 8086 的进程。多一次 HMAC 是微秒级，换的是
 * "服务端口意外暴露时后台仍然只认签名"。角色粗筛在网关，细筛在这里，两处都判。</p>
 */
@Service
@RequiredArgsConstructor
public class AdminIdentityService {

    private static final String BEARER = "Bearer ";

    private final AdminTokenCodec codec;
    private final AdminSessionService sessionService;

    /** @param authorization 原始 Authorization 头，允许为 null */
    public AdminClaims resolve(String authorization) {
        String token = stripBearer(authorization);
        TokenVerifyResult result = codec.verify(token, Instant.now().getEpochSecond());
        if (result.status() != TokenVerifyResult.Status.OK) {
            throw switch (result.status()) {
                case EXPIRED -> BizException.of(ErrorCode.TOKEN_EXPIRED);
                case BAD_SIGNATURE, MALFORMED -> BizException.of(ErrorCode.UNAUTHORIZED, "凭证无效");
                case OK -> new IllegalStateException("unreachable");
            };
        }
        AdminClaims claims = result.claims();
        if (sessionService.isRevoked(claims.jti())) {
            throw BizException.of(ErrorCode.SESSION_REVOKED);
        }
        Long bumpedAt = sessionService.bumpedAt(claims.uid());
        if (bumpedAt != null && claims.iat() < bumpedAt) {
            throw BizException.of(ErrorCode.SESSION_REVOKED, "该账号已改密或被停用，请重新登录");
        }
        return claims;
    }

    /** 解析并要求角色之一；角色不在列 → 40300（不是 40100，身份是合法的，只是权限不够） */
    public AdminClaims require(String authorization, String... allowedRoles) {
        AdminClaims claims = resolve(authorization);
        if (allowedRoles.length > 0 && Arrays.stream(allowedRoles).noneMatch(r -> r.equals(claims.role()))) {
            throw BizException.of(ErrorCode.FORBIDDEN,
                    "需要角色 " + String.join("/", allowedRoles) + "，当前 " + claims.role());
        }
        return claims;
    }

    private static String stripBearer(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return "";
        }
        return authorization.startsWith(BEARER) ? authorization.substring(BEARER.length()).trim() : authorization.trim();
    }
}
