package com.example.marketing.gateway.filter;

import com.example.marketing.common.security.AdminClaims;
import com.example.marketing.common.security.AdminTokenCodec;
import com.example.marketing.common.security.ConsumerClaims;
import com.example.marketing.common.security.ConsumerTokenCodec;
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

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
 * 消费者凭证在网关侧的判定。
 *
 * <p>为什么必须有这一份：{@code ConsumerRequestIdentity} 只做无状态验签，吊销位与整号作废
 * 时刻<b>只在这里查</b>。所以"一个已登出的会话还能不能用"这个问题，直连 account 端口测不出来
 * —— 那条路上根本没有 Redis 可查。这个 filter 是吊销语义唯一的执行点。</p>
 */
class ConsumerAuthFilterTest {

    private static final String SECRET = "unit-test-consumer-secret";
    /** 后台密钥刻意取不同的值：两套凭证不互通要落在 HMAC 层，不靠 claim 形状侥幸 */
    private static final String ADMIN_SECRET = "unit-test-admin-secret";
    private static final Instant NOW = Instant.now();
    /** 被删除的那层演示级假鉴权，留一个样本证明它现在进不来 */
    private static final String LEGACY_DEMO_TOKEN = "demo-token-123";

    private GatewayProperties properties;
    private ReactiveRedisTemplate<String, String> redis;
    private ReactiveValueOperations<String, String> valueOps;
    private ConsumerAuthFilter filter;
    private GatewayFilterChain chain;
    private ArgumentCaptor<ServerWebExchange> captured;
    /** 下游是否真的收到了请求 —— 单 filter 测试用 mock chain 验，串两个 filter 时只能这样记 */
    private boolean served;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new GatewayProperties();
        properties.getConsumer().setJwtSecret(SECRET);
        served = false;
        redis = mock(ReactiveRedisTemplate.class);
        valueOps = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(Mono.empty());
        when(redis.hasKey(anyString())).thenReturn(Mono.just(false));
        filter = new ConsumerAuthFilter(properties, redis, codec(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        captured = ArgumentCaptor.forClass(ServerWebExchange.class);
    }

    private ConsumerTokenCodec codec() {
        return new ConsumerTokenCodec(SECRET, Duration.ofSeconds(30));
    }

    private String access(long uid, String identifier, String jti) {
        long now = NOW.getEpochSecond();
        return codec().issue(new ConsumerClaims(uid, identifier, jti,
                ConsumerClaims.TYPE_ACCESS, now, now + 900));
    }

    /** 有效期已过的 access：超出 codec 的 30 秒容忍，必定判为 EXPIRED */
    private String expiredAccess(long uid, String identifier, String jti) {
        long past = NOW.getEpochSecond() - 3600;
        return codec().issue(new ConsumerClaims(uid, identifier, jti,
                ConsumerClaims.TYPE_ACCESS, past, past + 900));
    }

    private String refresh(long uid, String identifier, String jti) {
        long now = NOW.getEpochSecond();
        return codec().issue(new ConsumerClaims(uid, identifier, jti,
                ConsumerClaims.TYPE_REFRESH, now, now + 30 * 24 * 3600));
    }

    private MockServerWebExchange exchange(HttpMethod method, String path, String authorization) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(method, URI.create("http://gw" + path));
        if (authorization != null) {
            builder = builder.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return MockServerWebExchange.from(builder);
    }

    private MockServerWebExchange me(String authorization) {
        return exchange(HttpMethod.GET, "/api/auth/me", authorization);
    }

    private String responseBody(MockServerWebExchange exchange) {
        String body = exchange.getResponse().getBodyAsString().block();
        return body == null ? "" : body;
    }

    // ---- 覆盖面：整片 /api/**，公开读数靠例外放行 ----

    @Test
    @DisplayName("交易入口默认就要登录：没带凭证的领券被拒 40100")
    void businessPathsRequireLoginByDefault() {
        MockServerWebExchange exchange = exchange(HttpMethod.POST, "/api/coupon/grant", null);

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertTrue(responseBody(exchange).contains("40100"));
    }

    @Test
    @DisplayName("清单外冒出一条新路由时的默认答案是「要登录」，不是「谁都能调」")
    void unknownApiPathFailsClosed() {
        MockServerWebExchange exchange = exchange(HttpMethod.POST, "/api/withdrawals", null);

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertTrue(responseBody(exchange).contains("40100"),
                "这条断言钉的是方向：managed-paths 写 /api/** 而不是逐条枚举需要登录的路径");
    }

    @Test
    @DisplayName("公开读数在白名单里：游客能看活动详情、余量、秒杀列表")
    void guestReadsStayOpen() {
        for (String path : List.of("/api/activity/ACT2026001", "/api/activity/ACT2026001/budget/remain",
                "/api/seckill/activities", "/api/seckill/stock/SK2026001", "/api/coupon/stock/TPL001")) {
            GatewayFilterChain perPath = mock(GatewayFilterChain.class);
            when(perPath.filter(any())).thenReturn(Mono.empty());
            MockServerWebExchange exchange = exchange(HttpMethod.GET, path, null);

            filter.filter(exchange, perPath).block();

            verify(perPath, times(1)).filter(any());
            // 放行时网关不碰状态码；拦下来会显式写 401/403。比读响应体可靠（没写过体）
            assertNull(exchange.getResponse().getStatusCode(),
                    path + " 不该被拦下来");
        }
    }

    @Test
    @DisplayName("后台那半边靠 AdminAuthFilter 的 VERIFIED 标记让开 —— 少这一跳整片后台就归零")
    void adminSliceYieldsByMarker() {
        // 不带标记时 /api/admin/** 会撞上 /api/**，后台 token 被判"凭证无效"
        MockServerWebExchange unmarked = exchange(HttpMethod.GET, "/api/admin/users", null);
        filter.filter(unmarked, chain).block();
        assertTrue(responseBody(unmarked).contains("40100"), "前提：/api/admin/** 确实在 /api/** 的罩子里");

        MockServerWebExchange marked = exchange(HttpMethod.GET, "/api/admin/users", null);
        marked.getAttributes().put(AdminAuthFilter.VERIFIED, Boolean.TRUE);
        filter.filter(marked, chain).block();

        verify(chain, times(1)).filter(any());
    }

    @Test
    @DisplayName("把一条公开读数从 permit 清单拿掉，它立刻变成要登录 —— 例外清单是唯一开关")
    void permitListIsTheOnlySwitch() {
        properties.getConsumer().setPermitPaths(List.of("/api/auth/login"));
        filter = new ConsumerAuthFilter(properties, redis, codec(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        MockServerWebExchange exchange = exchange(HttpMethod.GET, "/api/seckill/activities", null);
        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertTrue(responseBody(exchange).contains("40100"));
    }

    @Test
    @DisplayName("过期 token 逛公开页不该被赶出去：按匿名放行，等到真要身份那次再拿 40101")
    void expiredTokenStillBrowsesAsGuest() {
        MockServerWebExchange exchange = exchange(HttpMethod.GET, "/api/seckill/activities",
                "Bearer " + expiredAccess(70001L, "demo", "jti-x"));

        filter.filter(exchange, chain).block();

        verify(chain, times(1)).filter(any());
    }

    @Test
    @DisplayName("公开页上带了枚伪造凭证：拒绝，不能让它和游客长成同一个形状")
    void forgedTokenOnOpenPathIsRejected() {
        MockServerWebExchange exchange = exchange(HttpMethod.GET, "/api/seckill/activities",
                "Bearer " + access(70001L, "demo", "jti-f") + "tampered");

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertTrue(responseBody(exchange).contains("40100"));
    }

    @Test
    @DisplayName("公开页带了合法凭证时照样注入身份：游客与登录用户看到的是同一条路径")
    void validTokenOnOpenPathInjectsIdentity() {
        String bearer = access(70001L, "demo", "jti-open");

        filter.filter(exchange(HttpMethod.GET, "/api/seckill/activities", "Bearer " + bearer), chain).block();

        verify(chain).filter(captured.capture());
        assertEquals("70001", captured.getValue().getRequest().getHeaders()
                .getFirst(ConsumerAuthFilter.UID_HEADER));
    }

    // ---- 免凭证的三个口 ----

    @Test
    @DisplayName("登录口无凭证放行并标记已判定，让 C 端静态 token 鉴权不再插手")
    void loginIsPermitted() {
        MockServerWebExchange exchange = exchange(HttpMethod.POST, "/api/auth/login", null);

        filter.filter(exchange, chain).block();

        verify(chain).filter(captured.capture());
        assertEquals(Boolean.TRUE, captured.getValue().getAttributes().get(ConsumerAuthFilter.VERIFIED));
    }

    @Test
    @DisplayName("登录口把外部塞进来的身份头清空 —— 少了 remove，下游会看到一个「未登录却带着 X-User-Id」的请求")
    void permitRouteStripsSpoofedIdentityHeaders() {
        MockServerWebExchange req = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://gw/api/auth/login")
                        .header(ConsumerAuthFilter.TOKEN_HEADER, "attacker-minted")
                        .header(ConsumerAuthFilter.UID_HEADER, "70001")
                        .header(ConsumerAuthFilter.NAME_HEADER, "demo"));

        filter.filter(req, chain).block();

        verify(chain).filter(captured.capture());
        HttpHeaders headers = captured.getValue().getRequest().getHeaders();
        assertNull(headers.getFirst(ConsumerAuthFilter.TOKEN_HEADER));
        assertNull(headers.getFirst(ConsumerAuthFilter.UID_HEADER));
        assertNull(headers.getFirst(ConsumerAuthFilter.NAME_HEADER));
    }

    // ---- 合法凭证 ----

    @Test
    @DisplayName("合法 access token：注入身份头、剥掉 Authorization、置 VERIFIED")
    void validTokenPassesWithIdentityHeaders() {
        String bearer = access(70001L, "demo", "jti-1");

        filter.filter(me("Bearer " + bearer), chain).block();

        verify(chain).filter(captured.capture());
        HttpHeaders headers = captured.getValue().getRequest().getHeaders();
        assertNull(headers.getFirst(HttpHeaders.AUTHORIZATION),
                "Authorization 必须剥掉：留着它，下游无法区分「网关验过」和「直连端口塞进来的」");
        assertEquals("70001", headers.getFirst(ConsumerAuthFilter.UID_HEADER));
        assertEquals("demo", headers.getFirst(ConsumerAuthFilter.NAME_HEADER));
        assertEquals("jti-1", headers.getFirst(ConsumerAuthFilter.JTI_HEADER));
        assertEquals(bearer, headers.getFirst(ConsumerAuthFilter.TOKEN_HEADER),
                "下游用同一个 codec 无状态验签，靠的是这一枚而不是裸身份头");
        assertEquals(Boolean.TRUE, captured.getValue().getAttributes().get(ConsumerAuthFilter.VERIFIED));
    }

    @Test
    @DisplayName("客户端注入的 X-User-Token 被网关自己那枚顶掉，不留成两条")
    void stripsClientSuppliedTokenHeader() {
        String bearer = access(70001L, "demo", "jti-1");
        MockServerWebExchange req = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://gw/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .header(ConsumerAuthFilter.TOKEN_HEADER, "attacker-minted"));

        filter.filter(req, chain).block();

        verify(chain).filter(captured.capture());
        List<String> forwarded = captured.getValue().getRequest().getHeaders()
                .get(ConsumerAuthFilter.TOKEN_HEADER);
        assertEquals(1, forwarded.size(), "不能把客户端注入的那一枚留在列表里");
        assertEquals(bearer, forwarded.get(0));
    }

    // ---- 凭证不互通：三类坏 token ----

    @Test
    @DisplayName("旧的演示级共享 token 打身份端点被拒 40100：那层假鉴权已经删除")
    void legacySharedTokenIsRejected() {
        MockServerWebExchange exchange = me("Bearer " + LEGACY_DEMO_TOKEN);
        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertTrue(responseBody(exchange).contains("40100"));
    }

    @Test
    @DisplayName("后台 token 打消费者端点被拒：不互通由独立密钥保证，与 claim 形状无关")
    void adminTokenIsRejectedOnConsumerPath() {
        String adminToken = new AdminTokenCodec(ADMIN_SECRET, Duration.ofSeconds(30))
                .issue(new AdminClaims(1L, "admin", "admin", 1, "jti-admin",
                        NOW.getEpochSecond(), NOW.getEpochSecond() + 900));

        MockServerWebExchange exchange = me("Bearer " + adminToken);
        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertTrue(responseBody(exchange).contains("40100"));
    }

    @Test
    @DisplayName("缺凭证 40100、乱码 40100、过期 40101 三种分流各归各位")
    void missingGarbageAndExpiredSeparate() {
        MockServerWebExchange noToken = me(null);
        filter.filter(noToken, chain).block();
        assertTrue(responseBody(noToken).contains("40100"));

        MockServerWebExchange garbage = me("Bearer not-a-jwt");
        filter.filter(garbage, chain).block();
        assertTrue(responseBody(garbage).contains("40100"));

        MockServerWebExchange expired = me("Bearer " + expiredAccess(70001L, "demo", "jti-exp"));
        filter.filter(expired, chain).block();
        assertTrue(responseBody(expired).contains("40101"), "过期要和无效分开：前端靠它决定是静默刷新还是跳登录");
    }

    @Test
    @DisplayName("拿 refresh 当 access 用被拒，且不泄露「这是一枚合法的 refresh」")
    void refreshTokenCannotBeUsedAsAccess() {
        MockServerWebExchange exchange = me("Bearer " + refresh(70001L, "demo", "jti-r"));

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        String body = responseBody(exchange);
        assertTrue(body.contains("40100"));
        assertFalse(body.contains("40101"), "type 检查排在签名与有效期之后，报什么都是「凭证无效」");
    }

    // ---- 吊销语义：只在这里执行 ----

    @Test
    @DisplayName("会话已登出 → 吊销位命中 40102（直连业务端口测不到这条，这里才是唯一执行点）")
    void revokedSessionIsRejected() {
        when(redis.hasKey("consumer:revoked:jti-2")).thenReturn(Mono.just(true));

        MockServerWebExchange exchange = me("Bearer " + access(70001L, "demo", "jti-2"));
        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertTrue(responseBody(exchange).contains("40102"));
    }

    @Test
    @DisplayName("iat 早于整号作废时刻 → 40102（改密/停用一次杀光所有会话）")
    void bumpedUserIsRejected() {
        when(redis.opsForValue().get("consumer:bump:70002"))
                .thenReturn(Mono.just(String.valueOf(NOW.getEpochSecond() + 10)));

        MockServerWebExchange exchange = me("Bearer " + access(70002L, "newbie", "jti-3"));
        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertTrue(responseBody(exchange).contains("40102"));
    }

    @Test
    @DisplayName("吊销位没命中才去查作废时刻 —— 单会话登出是最常见路径，不做无谓的第二跳")
    void revocationCheckShortCircuitsBeforeBump() {
        when(redis.hasKey("consumer:revoked:jti-4")).thenReturn(Mono.just(true));

        filter.filter(me("Bearer " + access(70001L, "demo", "jti-4")), chain).block();

        verify(valueOps, never()).get(anyString());
    }

    // ---- 密钥缺失与排序 ----

    @Test
    @DisplayName("未配 CONSUMER_JWT_SECRET：交易入口整片 40300，但游客读数与后台不受影响")
    void missingSecretClosesTransactionsNotTheWholeSite() {
        properties.getConsumer().setJwtSecret("");
        filter = new ConsumerAuthFilter(properties, redis, new ConsumerTokenCodec("", Duration.ofSeconds(30)),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        MockServerWebExchange txn = exchange(HttpMethod.POST, "/api/coupon/grant", null);
        filter.filter(txn, chain).block();
        assertTrue(responseBody(txn).contains("40300"),
                "缺密钥要判「未启用」而不是放行：静默降为免鉴权比拒绝服务更糟");

        // 同一条配置错误不该波及商详页和后台 —— 那会让故障半径从"C 端下单"扩成"整站打不开"
        MockServerWebExchange guest = exchange(HttpMethod.GET, "/api/seckill/activities", null);
        filter.filter(guest, chain).block();
        MockServerWebExchange admin = exchange(HttpMethod.GET, "/api/admin/users", null);
        admin.getAttributes().put(AdminAuthFilter.VERIFIED, Boolean.TRUE);
        filter.filter(admin, chain).block();
        verify(chain, times(2)).filter(any());
    }

    @Test
    @DisplayName("登录口在缺密钥时仍然放行：否则「配置错了」会表现为「登录页打不开」")
    void permitRoutesStillOpenWithoutSecret() {
        properties.getConsumer().setJwtSecret("");
        filter = new ConsumerAuthFilter(properties, redis, new ConsumerTokenCodec("", Duration.ofSeconds(30)),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        filter.filter(exchange(HttpMethod.POST, "/api/auth/login", null), chain).block();

        verify(chain, times(1)).filter(any());
    }

    @Test
    @DisplayName("拒绝路径也必须置 VERIFIED：漏了它，这条请求会继续往下走并被别的 filter 接手")
    void rejectionAlsoMarksVerified() {
        MockServerWebExchange exchange = me("Bearer not-a-jwt");

        filter.filter(exchange, chain).block();

        assertEquals(Boolean.TRUE, exchange.getAttributes().get(ConsumerAuthFilter.VERIFIED));
    }

    @Test
    @DisplayName("排序：必须晚于 AdminAuthFilter(-110)，否则后台路径会撞进 /api/** 被要消费者凭证")
    void runsAfterAdminFilter() {
        assertTrue(filter.getOrder() > -110);
        assertFalse(ConsumerAuthFilter.VERIFIED.isEmpty());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("第六批 W10：X-User-Name 剥控制字符（CR/LF 头注入会让 Netty 编码 500）")
    void nameHeaderControlCharsStripped() {
        assertEquals("safe", ConsumerAuthFilter.sanitize("safe"));
        assertEquals("okFAKE", ConsumerAuthFilter.sanitize("ok\r\nFAKE"));
        assertEquals("", ConsumerAuthFilter.sanitize(null));
        assertEquals("", ConsumerAuthFilter.sanitize("\r\n\r\n"));
    }
}
