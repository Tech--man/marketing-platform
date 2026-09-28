package com.example.marketing.gateway.filter;

import com.example.marketing.common.security.AdminClaims;
import com.example.marketing.common.security.AdminRoles;
import com.example.marketing.common.security.AdminTokenCodec;
import com.example.marketing.common.security.TokenVerifyResult;
import com.example.marketing.gateway.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 管理后台的凭证分区：{@code /api/admin/**} 只认后台自己签发的 JWT，
 * 与 C 端那枚共享的演示 token 互不相通。
 *
 * <p>判定过的请求会在 exchange 上留下 {@link #VERIFIED} 标记，
 * {@link AuthFilter} 见到就跳过自己的校验 —— 用标记而不是往 C 端白名单里加路径，
 * 是因为白名单一旦含 {@code /api/admin/**}，在漏配密钥时就变成"后台全程免鉴权"。
 * 标记由这里唯一写入，语义是"admin 这片已经判过"，不存在第三种解释。</p>
 *
 * <p>三件事在这里做完、不留给后台服务：验签（含吊销位与整号作废时刻）、角色粗筛、
 * 把 Authorization 剥掉换成 X-Admin-* 身份头。剥 Authorization 是刻意的：留着它，
 * 后台服务就无法区分"网关验过的凭证"和"客户端直连 8086 塞进来的串"。</p>
 */
@Slf4j
@Component
public class AdminAuthFilter implements GlobalFilter, Ordered {

    /** 已由本 filter 判定过（放行或拒绝都不会再交给 C 端鉴权） */
    public static final String VERIFIED = "mkt:admin-auth-verified";

    private static final String ADMIN_PREFIX = "/api/admin/";
    private static final String REVOKED_PREFIX = "admin:revoked:";
    private static final String BUMP_PREFIX = "admin:user:bump:";
    private static final String BEARER = "Bearer ";
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final Set<HttpMethod> WRITE_METHODS =
            Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    private final GatewayProperties properties;
    private final ReactiveRedisTemplate<String, String> redis;
    private final AdminTokenCodec codec;

    public AdminAuthFilter(GatewayProperties properties,
                           ReactiveRedisTemplate<String, String> redis,
                           AdminTokenCodec adminTokenCodec) {
        this.properties = properties;
        this.redis = redis;
        this.codec = adminTokenCodec;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith(ADMIN_PREFIX)) {
            return chain.filter(exchange);
        }
        if (isPermitted(path)) {
            return chain.filter(pass(exchange, null));
        }
        if (!StringUtils.hasText(properties.getAdmin().getJwtSecret())) {
            log.warn("[admin-auth] 未配置 ADMIN_JWT_SECRET，后台拒绝全部请求 path={}", path);
            return reject(exchange, HttpStatus.FORBIDDEN, 40300, "管理后台未启用：缺少 ADMIN_JWT_SECRET");
        }
        TokenVerifyResult result = codec.verify(tokenOf(exchange), Instant.now().getEpochSecond());
        if (result.status() != TokenVerifyResult.Status.OK) {
            return rejectedByStatus(exchange, result, path);
        }
        AdminClaims claims = result.claims();
        if (isWrite(exchange) && AdminRoles.READ_ONLY.equals(claims.role())) {
            return reject(exchange, HttpStatus.FORBIDDEN, 40300, "只读角色不能执行写操作");
        }
        // 两次 Redis 串行：吊销位命中就不必再查作废时刻（单会话登出是最常见路径）
        return redis.hasKey(REVOKED_PREFIX + claims.jti())
                .defaultIfEmpty(false)
                .flatMap(revoked -> revoked
                        ? reject(exchange, HttpStatus.UNAUTHORIZED, 40102, "会话已失效，请重新登录")
                        : checkBump(exchange, chain, claims, path));
    }

    private Mono<Void> checkBump(ServerWebExchange exchange, GatewayFilterChain chain,
                                 AdminClaims claims, String path) {
        return redis.opsForValue().get(BUMP_PREFIX + claims.uid())
                .defaultIfEmpty("")
                .flatMap(bumpedAt -> {
                    if (!bumpedAt.isEmpty() && claims.iat() < Long.parseLong(bumpedAt)) {
                        log.info("[admin-auth] 会话早于整号作废时刻，拒绝 user={}, path={}", claims.sub(), path);
                        return reject(exchange, HttpStatus.UNAUTHORIZED, 40102, "该账号已改密或被停用，请重新登录");
                    }
                    return chain.filter(pass(exchange, claims));
                });
    }

    private Mono<Void> rejectedByStatus(ServerWebExchange exchange, TokenVerifyResult result, String path) {
        return switch (result.status()) {
            case EXPIRED -> reject(exchange, HttpStatus.UNAUTHORIZED, 40101, "登录已过期，请重新登录");
            default -> {
                log.info("[admin-auth] 凭证无效 path={}, status={}", path, result.status());
                yield reject(exchange, HttpStatus.UNAUTHORIZED, 40100, "凭证无效");
            }
        };
    }

    /**
     * 重写请求：剥掉 Authorization，注入网关确认过的身份。
     * token 原文换成放进 {@code X-Admin-Token}：下游不需要区分 bearer 形状，
     * 也不必把原始 Authorization 一路带到业务进程。
     * claims 为 null 表示登录口 —— 此时连身份头都不给，避免"未登录也带着 X-Admin-User"
     * 被下游误信。
     */
    private ServerWebExchange pass(ServerWebExchange exchange, AdminClaims claims) {
        exchange.getAttributes().put(VERIFIED, Boolean.TRUE);
        // 先取原文再改头：tokenOf 读的就是这个未 mutation 的 exchange
        String token = tokenOf(exchange);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HttpHeaders.AUTHORIZATION);
                    headers.remove("X-Admin-User");
                    headers.remove("X-Admin-Role");
                    headers.remove("X-Admin-Jti");
                    headers.remove("X-Admin-Uid");
                    headers.remove("X-Admin-Token");
                    if (claims != null) {
                        headers.set("X-Admin-User", claims.sub());
                        headers.set("X-Admin-Role", claims.role());
                        headers.set("X-Admin-Jti", claims.jti());
                        headers.set("X-Admin-Uid", String.valueOf(claims.uid()));
                        // ③：业务服务只认这枚签名。裸 X-Admin-* 头对"直连 808x 的人"不设防，
                        // 而 ③ 之后那些端口上挂着改预算/改库存的写端点（段内 spec §3.2）。
                        // 这里不重新签发，只透传刚验过的那一枚，下游用同一个 codec 无状态验签。
                        headers.set("X-Admin-Token", token);
                    }
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, int code, String message) {
        exchange.getAttributes().put(VERIFIED, Boolean.TRUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", null);
        return GatewayResponses.writeJson(exchange.getResponse(), status, body);
    }

    private boolean isPermitted(String path) {
        return properties.getAdmin().getPermitPaths().stream()
                .anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    private boolean isWrite(ServerWebExchange exchange) {
        return WRITE_METHODS.contains(exchange.getRequest().getMethod());
    }

    private String tokenOf(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return "";
        }
        return header.startsWith(BEARER) ? header.substring(BEARER.length()).trim() : header.trim();
    }

    @Override
    public int getOrder() {
        // 必须先于 AuthFilter(-100)：否则后台请求会先被 C 端 demo token 判定挡下或放行
        return -110;
    }
}
