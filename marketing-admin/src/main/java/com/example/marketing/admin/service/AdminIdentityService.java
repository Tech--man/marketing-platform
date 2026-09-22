package com.example.marketing.admin.service;

import com.example.marketing.admin.security.AdminPrincipal;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.security.AdminClaims;
import com.example.marketing.common.security.AdminTokenCodec;
import com.example.marketing.common.security.TokenVerifyResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * "当前这个请求是谁"的唯一出处，两条来源：
 *
 * <ol>
 *   <li><b>经网关</b>：读 {@code X-Admin-*} 身份头。可信的理由不是"内部网络"这种口头约定，
 *       而是网关在处理 {@code /api/admin/**} 时既剥掉 Authorization、又把自己注入的
 *       {@code X-Admin-*} 先删后写（{@code AdminAuthFilter.pass}），客户端伪造的同名头到不了这里。</li>
 *   <li><b>直连服务端口</b>（绕过网关，如本机 curl 8085/8086）：头不在，就自己验签 +
 *       查吊销位与整号作废时刻。少了这条回退，"后台只经网关"就是一个纯口头承诺 ——
 *       而 LITE 服役档里 standalone 的 8085 是发布到宿主机的，同进程还有 C 端业务。</li>
 * </ol>
 *
 * <p>两条路径都失败一律 40100；角色不够是 40300（身份合法、权限不足，
 * 客户端对这两种的处理动作不同：一个重登，一个找管理员开权限）。</p>
 */
@Service
@RequiredArgsConstructor
public class AdminIdentityService {

    private static final String BEARER = "Bearer ";

    private final AdminTokenCodec codec;
    private final AdminSessionService sessionService;

    public AdminPrincipal resolve(HttpServletRequest request) {
        AdminPrincipal fromGateway = fromHeaders(request);
        if (fromGateway != null) {
            return fromGateway;
        }
        return fromToken(request.getHeader("Authorization"));
    }

    /** 解析并要求角色之一；不传 allowed 表示任意后台角色 */
    public AdminPrincipal require(HttpServletRequest request, String... allowedRoles) {
        AdminPrincipal principal = resolve(request);
        if (allowedRoles.length > 0 && !principal.hasRole(allowedRoles)) {
            throw BizException.of(ErrorCode.FORBIDDEN,
                    "需要角色 " + String.join("/", allowedRoles) + "，当前 " + principal.role());
        }
        return principal;
    }

    private AdminPrincipal fromHeaders(HttpServletRequest request) {
        String username = request.getHeader("X-Admin-User");
        String role = request.getHeader("X-Admin-Role");
        String jti = request.getHeader("X-Admin-Jti");
        String uid = request.getHeader("X-Admin-Uid");
        if (!StringUtils.hasText(username) || !StringUtils.hasText(role)
                || !StringUtils.hasText(jti) || !StringUtils.hasText(uid)) {
            // 缺任何一个都退回验签：半套头不能既当作"已过网关"又当作"信息不全无所谓"
            return null;
        }
        try {
            return new AdminPrincipal(Long.parseLong(uid), username, role, jti);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "凭证无效");
        }
    }

    private AdminPrincipal fromToken(String authorization) {
        String token = authorization == null || authorization.isBlank() ? ""
                : authorization.startsWith(BEARER)
                ? authorization.substring(BEARER.length()).trim() : authorization.trim();
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
        return new AdminPrincipal(claims.uid(), claims.sub(), claims.role(), claims.jti());
    }
}
