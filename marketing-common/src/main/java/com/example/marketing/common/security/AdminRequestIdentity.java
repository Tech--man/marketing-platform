package com.example.marketing.common.security;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.time.Instant;

/**
 * 业务服务侧的后台调用者识别：<b>只认签名 token</b>。
 *
 * <p>为什么不能像 admin 那样读 {@code X-Admin-*} 头就够了（母版 §6.0 原本这么写）：
 * 那套头的可信度来自"网关先删后写"，只对经网关的请求成立。③ 把这些端口变成了
 * 改预算 / 改库存的写入口，而 FULL 进程形态业务服务监听 {@code *:808x}、LITE 服役档的
 * standalone 8085 按设计发布到宿主机 —— 裸头等于"同网段里谁都能冒充 admin"。</p>
 *
 * <p>于是网关把它已经验过的那枚 token 透传成 {@code X-Admin-Token}（{@code Authorization}
 * 照旧剥掉），这里用与 admin 签发时同一个 {@link AdminTokenCodec} 无状态验签，
 * 身份字段一律取 claims。</p>
 *
 * <p>吊销位与"整号作废时刻"仍只在网关与 admin 判：要在业务侧也判，就得给四个进程各装一套
 * Redis 回退，那是第五份等值实现。代价（被吊销的会话直连业务端口可写到自然过期）
 * 写进段内 spec §9.1 的已知边界，不靠再加一层机制掩盖。</p>
 */
public class AdminRequestIdentity {

    public static final String TOKEN_HEADER = "X-Admin-Token";

    private final AdminTokenCodec codec;

    public AdminRequestIdentity(String secret, Duration skew) {
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException(
                    "ADMIN_JWT_SECRET 未配置：后台写端点宁可不服务，也不接受一枚谁都能伪造的身份头");
        }
        this.codec = new AdminTokenCodec(secret, skew);
    }

    /** 不传 allowedRoles 表示"任意后台角色"；传了则必须命中其一 */
    public AdminPrincipal require(HttpServletRequest request, String... allowedRoles) {
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || token.trim().isEmpty()) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "缺少后台凭证");
        }
        TokenVerifyResult result = codec.verify(token.trim(), Instant.now().getEpochSecond());
        if (result.status() != TokenVerifyResult.Status.OK) {
            throw switch (result.status()) {
                case EXPIRED -> BizException.of(ErrorCode.TOKEN_EXPIRED);
                case BAD_SIGNATURE, MALFORMED -> BizException.of(ErrorCode.UNAUTHORIZED, "凭证无效");
                case OK -> new IllegalStateException("unreachable");
            };
        }
        AdminClaims claims = result.claims();
        AdminPrincipal principal = new AdminPrincipal(claims.uid(), claims.sub(), claims.role(), claims.jti());
        if (allowedRoles.length > 0 && !principal.hasRole(allowedRoles)) {
            throw BizException.of(ErrorCode.FORBIDDEN,
                    "需要角色 " + String.join("/", allowedRoles) + "，当前 " + principal.role());
        }
        return principal;
    }
}
