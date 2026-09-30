package com.example.marketing.gateway.filter;

import com.example.marketing.common.security.ConsumerClaims;
import com.example.marketing.common.security.ConsumerTokenCodec;
import com.example.marketing.common.security.ConsumerVerifyResult;
import com.example.marketing.gateway.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpHeaders;
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

/**
 * 消费者凭证分区：{@code /api/auth/**} 只认 account 签发的 JWT，
 * 与后台 JWT、与 C 端那枚共享演示 token 三方互不相通。
 *
 * <p>判定过的请求在 exchange 上留下 {@link #VERIFIED} 标记，{@link AuthFilter} 见到就跳过
 * 自己的校验。用标记而不是往白名单里加路径，理由与后台完全同源：白名单是"谁都不许拦"，
 * 漏配密钥时那片就变成免鉴权；标记的语义只有"这一片已经有人判过了"。</p>
 *
 * <p><b>覆盖面是整片 {@code /api/**}</b>：需要身份的是默认，游客可看的是例外（见
 * {@link GatewayProperties.Consumer#permitPaths}）。方向反过来一次，新增一条交易路由就会
 * 默默免鉴权 —— 而它看起来一切正常。后台那半边由 {@code AdminAuthFilter} 先留下的
 * VERIFIED 标记让开，本 filter 不参与 {@code /api/admin/**} 的判定。</p>
 *
 * <p>吊销位与整号作废时刻<b>只在这里判</b>：业务侧的 {@code ConsumerRequestIdentity} 只做无
 * 状态验签。代价写进段内 spec 的已知边界 —— 被吊销的 access token 直连业务端口可用到自然过期。</p>
 */
@Slf4j
@Component
public class ConsumerAuthFilter implements GlobalFilter, Ordered {

    /** 已由本 filter 判定过（放行与拒绝都不再交给 C 端静态 token 鉴权） */
    public static final String VERIFIED = "mkt:consumer-auth-verified";

    public static final String UID_HEADER = "X-User-Id";
    public static final String NAME_HEADER = "X-User-Name";
    public static final String JTI_HEADER = "X-User-Jti";
    public static final String TOKEN_HEADER = "X-User-Token";

    private static final String REVOKED_PREFIX = "consumer:revoked:";
    private static final String BUMP_PREFIX = "consumer:bump:";
    private static final String BEARER = "Bearer ";
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final GatewayProperties properties;
    private final ReactiveRedisTemplate<String, String> redis;
    private final ConsumerTokenCodec codec;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public ConsumerAuthFilter(GatewayProperties properties,
                              ReactiveRedisTemplate<String, String> redis,
                              ConsumerTokenCodec consumerTokenCodec,
                              io.micrometer.core.instrument.MeterRegistry meters) {
        this.properties = properties;
        this.redis = redis;
        this.codec = consumerTokenCodec;
        this.meters = meters;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (Boolean.TRUE.equals(exchange.getAttributes().get(AdminAuthFilter.VERIFIED))) {
            // /api/admin/** 已被 -110 判过（放行与拒绝都算）。少了这一跳，托管清单写
            // /api/** 就会把后台路径也管进来，后台 token 在这里判成"凭证无效"，
            // 整个后台登录态归零 —— 而失败模式是"后台打不开"，很难联想到这条 filter。
            return chain.filter(exchange);
        }
        if (!isManaged(path)) {
            return chain.filter(exchange);
        }
        if (isPermitted(path)) {
            return openPath(exchange, chain, path);
        }
        if (!StringUtils.hasText(properties.getConsumer().getJwtSecret())) {
            log.warn("[consumer-auth] 未配置 CONSUMER_JWT_SECRET，受保护的身份端点拒绝全部请求 path={}", path);
            return reject(exchange, HttpStatus.FORBIDDEN, 40300, "消费者身份服务未启用：缺少 CONSUMER_JWT_SECRET");
        }
        ConsumerVerifyResult result = codec.verifyAccess(tokenOf(exchange), Instant.now().getEpochSecond());
        if (result.status() != ConsumerVerifyResult.Status.OK) {
            return rejectedByStatus(exchange, result, path);
        }
        return checkRevocation(exchange, chain, result.claims(), path);
    }

    /**
     * 免登录的口（登录/注册/刷新，以及游客本来就该看到的目录类读数）。
     *
     * <p>三种情形分开：没带凭证 → 匿名放行；带了一枚过期 access → 也按匿名放行，
     * 因为"逛到一半 token 到期就 401 赶出商详页"是错的，客户端会在真正需要身份的那次
     * 调用上拿到 40101 并去刷新；带了但<b>验不过</b>（乱码、错签、拿 refresh 冒充）→ 拒绝，
     * 否则伪造凭证的人和游客在下游长成同一个形状。</p>
     */
    private Mono<Void> openPath(ServerWebExchange exchange, GatewayFilterChain chain, String path) {
        String token = tokenOf(exchange);
        if (!StringUtils.hasText(token) || !StringUtils.hasText(properties.getConsumer().getJwtSecret())) {
            return chain.filter(pass(exchange, null));
        }
        ConsumerVerifyResult result = codec.verifyAccess(token, Instant.now().getEpochSecond());
        return switch (result.status()) {
            case OK -> checkRevocation(exchange, chain, result.claims(), path);
            case EXPIRED -> chain.filter(pass(exchange, null));
            default -> rejectedByStatus(exchange, result, path);
        };
    }

    /**
     * 吊销位与整号作废时刻：两次 Redis 串行，命中就不必再查第二跳（单会话登出是最常见路径）。
     *
     * <p><b>根因 C 降级（2026-09-29 审查）</b>：Redis 异常时退化为"仅验签放行 + 计数告警"，
     * 不再是整个入口 5xx。代价与 Redis 被清空时相同（已声明的已知边界）：已吊销的
     * access token 可用到自然过期，上界 accessTtl（15 分钟）——用有限时间的降级窗口
     * 换"Redis 故障不放大成全站不可用"。恢复即自愈。</p>
     */
    private Mono<Void> checkRevocation(ServerWebExchange exchange, GatewayFilterChain chain,
                                       ConsumerClaims claims, String path) {
        return redis.hasKey(REVOKED_PREFIX + claims.jti())
                .defaultIfEmpty(false)
                .onErrorResume(e -> {
                    degraded("revoked-check", e);
                    return chain.filter(pass(exchange, claims)).then(Mono.just(false));
                })
                .flatMap(revoked -> revoked
                        ? reject(exchange, HttpStatus.UNAUTHORIZED, 40102, "会话已失效，请重新登录")
                        : checkBump(exchange, chain, claims, path));
    }

    private Mono<Void> checkBump(ServerWebExchange exchange, GatewayFilterChain chain,
                                 ConsumerClaims claims, String path) {
        return redis.opsForValue().get(BUMP_PREFIX + claims.uid())
                .defaultIfEmpty("")
                .onErrorResume(e -> {
                    degraded("bump-check", e);
                    return chain.filter(pass(exchange, claims)).then(Mono.just(""));
                })
                .flatMap(bumpedAt -> {
                    if (!bumpedAt.isEmpty() && claims.iat() < Long.parseLong(bumpedAt)) {
                        log.info("[consumer-auth] 会话早于整号作废时刻，拒绝 uid={}, path={}", claims.uid(), path);
                        return reject(exchange, HttpStatus.UNAUTHORIZED, 40102, "该账号已改密或被停用，请重新登录");
                    }
                    return chain.filter(pass(exchange, claims));
                });
    }

    /** 吊销链 Redis 不可用的降级计数 + 告警（详见 checkRevocation 的根因 C 注释） */
    private void degraded(String stage, Throwable e) {
        meters.counter("marketing.gateway.auth.degraded", "filter", "consumer", "stage", stage).increment();
        log.warn("[consumer-auth] {} 不可用，退化为仅验签放行（bounded by accessTtl，恢复即自愈）: {}",
                stage, e.toString());
    }

    private Mono<Void> rejectedByStatus(ServerWebExchange exchange, ConsumerVerifyResult result, String path) {
        return switch (result.status()) {
            case EXPIRED -> reject(exchange, HttpStatus.UNAUTHORIZED, 40101, "登录已过期，请重新登录");
            default -> {
                log.info("[consumer-auth] 凭证无效 path={}, status={}", path, result.status());
                yield reject(exchange, HttpStatus.UNAUTHORIZED, 40100, "凭证无效");
            }
        };
    }

    /**
     * 重写请求：剥掉 Authorization，注入网关确认过的身份。
     * token 原文放进 {@code X-User-Token} —— 下游用同一个 codec 无状态验签，
     * 这样"直连业务端口塞一个裸 X-User-Id"这条路从源头就不成立（同 ③ 对后台的处理）。
     */
    private ServerWebExchange pass(ServerWebExchange exchange, ConsumerClaims claims) {
        exchange.getAttributes().put(VERIFIED, Boolean.TRUE);
        String token = tokenOf(exchange);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HttpHeaders.AUTHORIZATION);
                    headers.remove(UID_HEADER);
                    headers.remove(NAME_HEADER);
                    headers.remove(JTI_HEADER);
                    headers.remove(TOKEN_HEADER);
                    if (claims != null) {
                        headers.set(UID_HEADER, String.valueOf(claims.uid()));
                        // W10（2026-09-29 审查收口）：claims.sub 未来可能来自注册输入，
                        // 带进 header 前剥掉控制字符——CR/LF 会让 Netty 编码抛 500
                        headers.set(NAME_HEADER, sanitize(claims.sub()));
                        headers.set(JTI_HEADER, claims.jti());
                        headers.set(TOKEN_HEADER, token);
                    }
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    /** 剥掉控制字符（防 header 注入与 Netty 编码炸）。X-Admin-User 侧同用 */
    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (char ch : value.toCharArray()) {
            if (ch >= 0x20 && ch != 0x7F) {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, int code, String message) {
        exchange.getAttributes().put(VERIFIED, Boolean.TRUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", null);
        return GatewayResponses.writeJson(exchange.getResponse(), status, body);
    }

    /** 归本 filter 管的路径。P2 扩到业务入口时改的是配置，不是这段代码 */
    private boolean isManaged(String path) {
        return properties.getConsumer().getManagedPaths().stream()
                .anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    private boolean isPermitted(String path) {
        return properties.getConsumer().getPermitPaths().stream()
                .anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
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
        // 必须先于 AuthFilter(-100)：否则消费者请求会先被 C 端 demo token 判定挡下或放行；
        // 也必须后于 AdminAuthFilter(-110)：/api/admin/** 归它，两不相犯
        return -105;
    }
}
