package com.example.marketing.admin.service;

import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.security.AdminClaims;
import com.example.marketing.common.security.AdminRequestIdentity;
import com.example.marketing.common.security.AdminTokenCodec;
import com.example.marketing.common.security.TokenVerifyResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * "当前这个请求是谁"的唯一出处，两条来源、同一把尺子：<b>只认验签 token</b>。
 *
 * <ol>
 *   <li><b>经网关</b>：网关验完后把 Authorization 剥掉、token 原文透传成
 *       {@code X-Admin-Token}（{@code AdminAuthFilter.pass}，与业务服务的
 *       {@code AdminRequestIdentity} 同一约定）。这里取这枚 token 验签。</li>
 *   <li><b>直连服务端口</b>（绕过网关，如本机 curl 8085/8086）：Authorization
 *       Bearer，自己验签 + 查吊销位与整号作废时刻。</li>
 * </ol>
 *
 * <p><b>为什么不认 {@code X-Admin-User/Role/Jti/Uid} 身份头</b>（2026-09-29 架构审查 H1
 * 收口）：那套头的可信度只来自"网关先删后写"，对经网关的请求成立、对直连请求不成立——
 * 而 LITE 服役档 standalone 的 8085 发布在宿主机上、FULL 进程形态 8086 绑 {@code *}。
 * 早期版本在这里留过"四头齐全即免验签"的快路径，等于把最危险的一组端点（在线配置、
 * 停用账号、踢会话）交给了任何一个能触达端口的人。业务服务侧早已只认 {@code X-Admin-Token}
 * （段内 spec §3.2），admin 自己必须是同一纪律。头从此只当搬运工，身份一律取 claims。</p>
 *
 * <p>经网关的请求网关已查过一次吊销位，这里再查一次：一次 hasKey + 一次 get 的代价，
 * 换"admin 进程不依赖网关的单点正确性"——直连与经网关两条路径走完全相同的判定。</p>
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
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public AdminPrincipal resolve(HttpServletRequest request) {
        // 网关流量 Authorization 已被剥掉，只剩 X-Admin-Token；直连流量则带 Authorization。
        // 两者都有时以网关透传的为准：它一定是被验过的那枚，Authorization 反而可能是客户端塞的。
        String relayed = request.getHeader(AdminRequestIdentity.TOKEN_HEADER);
        String authorization = StringUtils.hasText(relayed) ? relayed
                : request.getHeader(HttpHeaders.AUTHORIZATION);
        return fromToken(authorization);
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
        // W3.1（2026-09-30 第二轮复审）：Redis 查询与网关同口径降级——fail-open
        // （仅验签放行 + degraded 计数），代价上界 accessTtl 内已吊销 token 仍可用，
        // 与 Redis 被清空时相同（网关 AdminAuthFilter 根因 C 的同款取舍）。原实现
        // 这里无降级：Redis 一挂全部 /api/admin/** 50000，网关精心做的降级被服务侧
        // fail-closed 击穿。3s 命令超时 × 两跳的长尾也一并消除。
        try {
            if (sessionService.isRevoked(claims.jti())) {
                throw BizException.of(ErrorCode.SESSION_REVOKED);
            }
            Long bumpedAt = sessionService.bumpedAt(claims.uid());
            if (bumpedAt != null && claims.iat() < bumpedAt) {
                throw BizException.of(ErrorCode.SESSION_REVOKED, "该账号已改密或被停用，请重新登录");
            }
        } catch (BizException be) {
            throw be;
        } catch (RuntimeException e) {
            meters.counter("marketing.admin.auth.degraded", "stage", "identity-check").increment();
            org.slf4j.LoggerFactory.getLogger(AdminIdentityService.class).warn(
                    "[admin-identity] 吊销位/作废时刻查询不可用，退化为仅验签放行"
                            + "（bounded by accessTtl，恢复即自愈）: {}", e.toString());
        }
        return new AdminPrincipal(claims.uid(), claims.sub(), claims.role(), claims.jti());
    }
}
