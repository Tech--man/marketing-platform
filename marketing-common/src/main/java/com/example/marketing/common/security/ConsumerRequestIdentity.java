package com.example.marketing.common.security;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.time.Instant;

/**
 * 业务服务侧的消费者识别：<b>只认签名 token</b>，与 {@link AdminRequestIdentity} 同一条理由。
 *
 * <p>网关会先删后写 {@code X-User-Id}/{@code X-User-Jti}，但那两个头的可信度来自网关，
 * 只对经网关的请求成立 —— FULL 形态业务服务监听 {@code *:808x}、LITE 的 standalone 8085
 * 按设计发布到宿主机，直连端口的人随手一个 {@code X-User-Id: 70001} 就能读走任何人的卡包。
 * 所以身份字段一律从验过的 claims 里取，头只是 token 的搬运工。</p>
 *
 * <p>取 token 的顺序：先 {@code X-User-Token}（网关透传它已验过的那枚），
 * 再回落到 {@code Authorization: Bearer}（直连业务端口、或网关还没升级的过渡期）。
 * 两条路走的是同一个验签，没有"某条路少验一步"的分支。</p>
 *
 * <p>吊销位与整号作废时刻仍只在网关与 account 服务判：要在每个业务进程也判，
 * 就得给它们各装一套 Redis 回退，那是第五份等值实现。代价（被吊销的 access token
 * 直连业务端口可用到自然过期，上限 accessTtl）与后台同形，写进段内 spec 的已知边界。</p>
 */
public class ConsumerRequestIdentity {

    public static final String TOKEN_HEADER = "X-User-Token";
    public static final String UID_HEADER = "X-User-Id";
    public static final String JTI_HEADER = "X-User-Jti";

    private static final String BEARER = "Bearer ";

    private final ConsumerTokenCodec codec;

    public ConsumerRequestIdentity(String secret, Duration skew) {
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException(
                    "CONSUMER_JWT_SECRET 未配置：C 端交易入口宁可不服务，也不接受一枚谁都能伪造的身份头");
        }
        this.codec = new ConsumerTokenCodec(secret, skew);
    }

    /** 解析当前登录消费者；缺失/无效一律 40100，过期单独给 40101 好让前端知道该刷新 */
    public ConsumerPrincipal require(HttpServletRequest request) {
        String token = extract(request);
        if (token == null || token.isBlank()) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "缺少登录凭证");
        }
        ConsumerVerifyResult result = codec.verifyAccess(token.trim(), Instant.now().getEpochSecond());
        if (result.status() != ConsumerVerifyResult.Status.OK) {
            throw switch (result.status()) {
                case EXPIRED -> BizException.of(ErrorCode.TOKEN_EXPIRED);
                case BAD_SIGNATURE, MALFORMED, WRONG_TYPE -> BizException.of(ErrorCode.UNAUTHORIZED, "凭证无效");
                case OK -> new IllegalStateException("unreachable");
            };
        }
        ConsumerClaims claims = result.claims();
        return new ConsumerPrincipal(claims.uid(), claims.sub(), claims.jti());
    }

    /** 可选登录：没带凭证返回 null（如首页游客态），带了但无效仍然抛错——不能把伪造当游客 */
    public ConsumerPrincipal requireOrNull(HttpServletRequest request) {
        String token = extract(request);
        return token == null || token.isBlank() ? null : require(request);
    }

    /** 只验签不抛业务异常，供网关之外的自校验场景使用 */
    public ConsumerVerifyResult verify(String token, String expectedType) {
        return codec.verify(token, Instant.now().getEpochSecond(), expectedType);
    }

    private static String extract(HttpServletRequest request) {
        String token = request.getHeader(TOKEN_HEADER);
        if (token != null && !token.isBlank()) {
            return token;
        }
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            return null;
        }
        return header.startsWith(BEARER) ? header.substring(BEARER.length()) : header;
    }
}
