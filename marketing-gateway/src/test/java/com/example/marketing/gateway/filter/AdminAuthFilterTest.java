package com.example.marketing.gateway.filter;

import com.example.marketing.common.security.AdminClaims;
import com.example.marketing.common.security.AdminTokenCodec;
import com.example.marketing.gateway.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台/ C 端两套凭证的分区。这里钉的是"互不相通"这件事本身：
 * C 端 demo token 打后台必须被拒（否则账号体系形同给 C 端口令开了个管理入口），
 * 后台 token 打 C 端也必须被拒（AuthFilter 只认自己的 token，这条由它自己的测试保证）。
 */
class AdminAuthFilterTest {

    private static final String SECRET = "unit-test-secret";
    /** 与 filter 内部同一时钟：签发与校验都取"现在"，不需要为一个测试引入时钟接缝 */
    private static final Instant NOW = Instant.now();
    /** 演示级静态 token 那一层已删除，这里只留一个"非 JWT 的共享串"作为反面样本 */
    private static final String LEGACY_DEMO_TOKEN = "demo-token-123";

    private GatewayProperties properties;
    private ReactiveRedisTemplate<String, String> redis;
    private AdminAuthFilter filter;
    private GatewayFilterChain chain;
    private ArgumentCaptor<ServerWebExchange> captured;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new GatewayProperties();
        properties.getAdmin().setJwtSecret(SECRET);
        redis = mock(ReactiveRedisTemplate.class);
        ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(Mono.empty());
        when(redis.hasKey(anyString())).thenReturn(Mono.just(false));
        filter = new AdminAuthFilter(properties, redis, codec(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        captured = ArgumentCaptor.forClass(ServerWebExchange.class);
    }

    private AdminTokenCodec codec() {
        return new AdminTokenCodec(SECRET, Duration.ofSeconds(30));
    }

    /** 用固定时刻签一个 token，和校验时的 nowSupplier 对齐 */
    private String token(long uid, String user, String role, String jti) {
        long now = NOW.getEpochSecond();
        return codec().issue(new AdminClaims(uid, user, role, 1, jti, now, now + 900));
    }

    private MockServerWebExchange exchange(HttpMethod method, String path, String authorization) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(method,
                java.net.URI.create("http://gw" + path));
        if (authorization != null) {
            builder = builder.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return MockServerWebExchange.from(builder);
    }

    private MockServerWebExchange adminGet(String authorization) {
        return exchange(HttpMethod.GET, "/api/admin/users", authorization);
    }

    @Test
    @DisplayName("非后台路径原样穿过，且不留下 VERIFIED 标记（留了就等于给 C 端开了免鉴权）")
    void ignoresNonAdminPaths() {
        MockServerWebExchange exchange = exchange(HttpMethod.GET, "/api/activity/ACT2026001", null);

        filter.filter(exchange, chain).block();

        verify(chain, times(1)).filter(any());
        assertNull(exchange.getAttributes().get(AdminAuthFilter.VERIFIED));
    }

    @Test
    @DisplayName("登录口无凭证放行，并标记已判定，让 C 端鉴权不再插手")
    void loginIsPermitted() {
        MockServerWebExchange exchange = exchange(HttpMethod.POST, "/api/admin/auth/login", null);

        filter.filter(exchange, chain).block();

        verify(chain).filter(captured.capture());
        assertEquals(Boolean.TRUE, captured.getValue().getAttributes().get(AdminAuthFilter.VERIFIED));
    }

    @Test
    @DisplayName("合法后台 token：注入身份头、剥掉 Authorization、置 VERIFIED")
    void validAdminTokenPassesWithIdentityHeaders() {
        MockServerWebExchange exchange = adminGet("Bearer " + token(1L, "admin", "admin", "jti-1"));

        filter.filter(exchange, chain).block();

        verify(chain).filter(captured.capture());
        HttpHeaders headers = captured.getValue().getRequest().getHeaders();
        assertNull(headers.getFirst(HttpHeaders.AUTHORIZATION),
                "Authorization 必须剥掉：留着它，后台服务无法区分'网关已验过'和'客户端直连塞进来的'");
        assertEquals("admin", headers.getFirst("X-Admin-User"));
        assertEquals("admin", headers.getFirst("X-Admin-Role"));
        assertEquals("jti-1", headers.getFirst("X-Admin-Jti"));
        assertEquals("1", headers.getFirst("X-Admin-Uid"));
        assertEquals(Boolean.TRUE, captured.getValue().getAttributes().get(AdminAuthFilter.VERIFIED));
    }

    @Test
    @DisplayName("C 端 demo token 打后台被拒，且不进 chain")
    void cSideTokenIsRejectedOnAdminPath() {
        MockServerWebExchange exchange = adminGet("Bearer " + LEGACY_DEMO_TOKEN);

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertTrue(responseBody(exchange).contains("40100"));
    }

    @Test
    @DisplayName("口令改对但会话被吊销 → 40102，提示与过期分开")
    void revokedSessionIsRejected() {
        when(redis.hasKey("admin:revoked:jti-2")).thenReturn(Mono.just(true));

        MockServerWebExchange exchange = adminGet("Bearer " + token(1L, "admin", "admin", "jti-2"));
        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertTrue(responseBody(exchange).contains("40102"));
    }

    @Test
    @DisplayName("iat 早于整号作废时刻 → 40102（改密/停用杀光会话）")
    void bumpedUserIsRejected() {
        when(redis.opsForValue().get("admin:user:bump:3")).thenReturn(Mono.just(String.valueOf(
                NOW.getEpochSecond() + 10)));

        MockServerWebExchange exchange = adminGet("Bearer " + token(3L, "admin", "admin", "jti-3"));
        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertTrue(responseBody(exchange).contains("40102"));
    }

    @Test
    @DisplayName("只读角色的写操作在网关就挡掉（粗筛）")
    void readOnlyRoleCannotWrite() {
        MockServerWebExchange exchange = exchange(HttpMethod.POST, "/api/admin/cache/reheat",
                "Bearer " + token(4L, "viewer", "read-only", "jti-4"));

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertTrue(responseBody(exchange).contains("40300"));
    }

    @Test
    @DisplayName("只读角色的读操作放行")
    void readOnlyRoleCanRead() {
        MockServerWebExchange exchange = adminGet("Bearer " + token(4L, "viewer", "read-only", "jti-4"));

        filter.filter(exchange, chain).block();

        verify(chain, times(1)).filter(any());
    }

    @Test
    @DisplayName("未配 ADMIN_JWT_SECRET：后台整片拒绝，但网关与 C 端不受影响")
    void missingSecretFailsClosedForAdminOnly() {
        properties.getAdmin().setJwtSecret("");
        filter = new AdminAuthFilter(properties, redis, new AdminTokenCodec("", Duration.ofSeconds(30)),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        MockServerWebExchange exchange = adminGet("Bearer " + token(1L, "admin", "admin", "jti-1"));

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertTrue(responseBody(exchange).contains("40300"));
        // 同一个 filter 对 C 端路径完全不介入，所以 C 端流量不会因为后台少配一个密钥而中断
        MockServerWebExchange cSide = exchange(HttpMethod.GET, "/api/seckill/SK2026001", null);
        filter.filter(cSide, chain).block();
        verify(chain, times(1)).filter(any());
    }

    private String responseBody(MockServerWebExchange exchange) {
        String body = exchange.getResponse().getBodyAsString().block();
        return body == null ? "" : body;
    }

    @Test
    @DisplayName("必须排在消费者鉴权之前：托管清单罩住整片 /api/**，后台要靠先落 VERIFIED 让开")
    void runsBeforeConsumerAuthFilter() {
        assertTrue(filter.getOrder() < new ConsumerAuthFilter(properties, redis,
                new com.example.marketing.common.security.ConsumerTokenCodec("x", java.time.Duration.ZERO),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
                .getOrder());
        assertFalse(AdminAuthFilter.VERIFIED.isEmpty());
    }

    // ---- ③：把已验签的 token 透传给业务服务（业务侧不采信裸身份头） ----

    @Test
    @DisplayName("下游收到 X-Admin-Token：业务服务要靠它验签，裸 X-Admin-* 头对直连端口不设防")
    void forwardsVerifiedTokenToDownstream() {
        String bearer = token(1L, "admin", "admin", "jti-1");

        filter.filter(adminGet("Bearer " + bearer), chain).block();

        verify(chain).filter(captured.capture());
        HttpHeaders headers = captured.getValue().getRequest().getHeaders();
        assertEquals(bearer, headers.getFirst("X-Admin-Token"));
        assertNull(headers.getFirst(HttpHeaders.AUTHORIZATION), "原始 bearer 照旧剥掉");
    }

    @Test
    @DisplayName("已鉴权路径：客户端注入的 X-Admin-Token 被网关自己那枚顶掉")
    void stripsClientSuppliedTokenHeader() {
        String bearer = token(1L, "admin", "admin", "jti-1");
        MockServerWebExchange req = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://gw/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .header("X-Admin-Token", "attacker-minted"));

        filter.filter(req, chain).block();

        verify(chain).filter(captured.capture());
        HttpHeaders headers = captured.getValue().getRequest().getHeaders();
        assertEquals(1, headers.get("X-Admin-Token").size(), "不能把客户端注入的那一枚留在列表里");
        assertEquals(bearer, headers.getFirst("X-Admin-Token"), "留下的必须是网关验过并透传的那枚");
    }

    @Test
    @DisplayName("免鉴权登录口：外部塞进来的身份头必须被清空（这条才是 remove 的真判据）")
    void permitRouteStripsSpoofedIdentityHeaders() {
        properties.getAdmin().setPermitPaths(java.util.List.of("/api/admin/auth/login"));
        MockServerWebExchange req = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://gw/api/admin/auth/login")
                        .header("X-Admin-Token", "attacker-minted")
                        .header("X-Admin-Role", "admin")
                        .header("X-Admin-Uid", "1"));

        filter.filter(req, chain).block();

        verify(chain).filter(captured.capture());
        HttpHeaders headers = captured.getValue().getRequest().getHeaders();
        // 这条路径上 claims 为 null、没有 set 会顶掉它们 —— 少了 remove，
        // 下游就会看到一个"未登录但带着 admin 身份"的请求
        assertNull(headers.getFirst("X-Admin-Token"), "登录口不给任何 token");
        assertNull(headers.getFirst("X-Admin-Role"), "未登录也不该带着 X-Admin-Role");
        assertNull(headers.getFirst("X-Admin-Uid"));
    }
}
