package com.example.marketing.account.service;

import com.example.marketing.account.config.AccountProperties;
import com.example.marketing.account.dto.TokenPairVO;
import com.example.marketing.account.infrastructure.entity.ConsumerSessionEntity;
import com.example.marketing.account.infrastructure.entity.ConsumerUserEntity;
import com.example.marketing.account.infrastructure.mapper.ConsumerUserMapper;
import com.example.marketing.account.security.ConsumerLoginGuard;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.security.ConsumerTokenCodec;
import com.example.marketing.common.security.ConsumerVerifyResult;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录/刷新/改密的判定语义。挑的都是"错了不会立刻炸、但安全语义已经反了"的那几条：
 * 枚举防护、吊销时机、轮换重放。
 */
class ConsumerAuthServiceTest {

    private static final String SECRET = "unit-test-consumer-secret-123456";
    private static final String PASSWORD = "demo123456";
    /** 与 ConsumerAuthService 里那个私有常量对齐：黑名单键 = 前缀 + 旧 refresh 的 SHA-256 */
    private static final String USED_PREFIX = "consumer:refresh:used:";

    private ConsumerUserMapper userMapper;
    private ConsumerSessionService sessionService;
    private ConsumerEventService events;
    private ConsumerLoginGuard guard;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private AccountProperties properties;
    private ConsumerAuthService service;

    /**
     * Wrappers.lambdaUpdate(...) 要查实体的 TableInfo/lambda 缓存，而那份缓存由
     * MyBatis-Plus 注册 mapper 时填充。纯单测里 mapper 是 mock、没有 SqlSessionFactory，
     * 不预热就会抛 "can not find lambda cache for this entity"。
     * 这是测试脚手架的缺口而不是产品 bug：真实装配里缓存一定在。显式预热，
     * 好让 service 能继续用类型安全的 lambda 条件而不必退回字符串列名。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, ConsumerUserEntity.class);
        TableInfoHelper.initTableInfo(assistant, ConsumerSessionEntity.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userMapper = mock(ConsumerUserMapper.class);
        sessionService = mock(ConsumerSessionService.class);
        events = mock(ConsumerEventService.class);
        guard = mock(ConsumerLoginGuard.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        // 模拟 DB 的 AUTO_INCREMENT 回写：MyBatis-Plus 在真实装配里会把生成的 id 填回实体，
        // 注册流程紧接着就要用这个 id 签发会话。mock 不会自己做这件事。
        when(userMapper.insert(any(ConsumerUserEntity.class))).thenAnswer(inv -> {
            ConsumerUserEntity u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(70002L);
            }
            return 1;
        });

        properties = new AccountProperties();
        properties.setJwtSecret(SECRET);
        service = new ConsumerAuthService(userMapper, sessionService, events, guard,
                new ConsumerTokenCodec(SECRET, Duration.ofSeconds(30)),
                new BCryptPasswordEncoder(), properties, redis);
    }

    private ConsumerUserEntity user(String status) {
        ConsumerUserEntity u = new ConsumerUserEntity();
        u.setId(70001L);
        u.setIdentifier("demo");
        u.setPasswordHash(new BCryptPasswordEncoder().encode(PASSWORD));
        u.setNickname("演示消费者");
        u.setStatus(status);
        u.setPwdVersion(1);
        u.setFailCount(0);
        u.setCreateTime(LocalDateTime.now());
        return u;
    }

    private BizException expectBiz(Runnable call) {
        return assertThrows(BizException.class, call::run);
    }

    @Test
    @DisplayName("登录成功签出的 access token 可被验回，uid 与 jti 都对得上")
    void loginIssuesVerifiableToken() {
        when(userMapper.selectOne(any())).thenReturn(user("ACTIVE"));
        TokenPairVO pair = service.login("demo", PASSWORD, "1.2.3.4", "junit");

        assertNotNull(pair.accessToken());
        assertNotNull(pair.refreshToken());
        assertEquals(properties.getAccessTtlSeconds(), pair.expiresInSeconds());
        ConsumerVerifyResult r = new ConsumerTokenCodec(SECRET, Duration.ofSeconds(30))
                .verifyAccess(pair.accessToken(), java.time.Instant.now().getEpochSecond());
        assertEquals(ConsumerVerifyResult.Status.OK, r.status());
        assertEquals(70001L, r.claims().uid());
        // 会话必须落库，否则登出/吊销无处可查
        verify(sessionService, times(1)).record(any(ConsumerSessionEntity.class));
    }

    @Test
    @DisplayName("口令错与账号不存在回同一句话：不给枚举者分辨依据")
    void badCredentialsAndUnknownUserLookIdentical() {
        when(userMapper.selectOne(any())).thenReturn(null);
        BizException unknown = expectBiz(() -> service.login("ghost", "whatever12", "1.1.1.1", "junit"));

        when(userMapper.selectOne(any())).thenReturn(user("ACTIVE"));
        BizException wrongPw = expectBiz(() -> service.login("demo", "totally-wrong", "1.1.1.1", "junit"));

        assertEquals(unknown.getMessage(), wrongPw.getMessage());
    }

    @Test
    @DisplayName("停用账号对外也说「账号或口令不正确」，但事件里记的是 DISABLED")
    void disabledLeaksNothingExternallyButIsAudited() {
        when(userMapper.selectOne(any())).thenReturn(user("DISABLED"));
        BizException e = expectBiz(() -> service.login("demo", PASSWORD, "1.1.1.1", "junit"));

        assertTrue(e.getMessage().contains("账号或口令不正确"), "对外文案不得暴露停用事实");
        verify(events).record(eq("LOGIN_FAILED"), anyLong(), eq("demo"), any(),
                eq("1.1.1.1"), eq("junit"), eq("DISABLED"));
    }

    @Test
    @DisplayName("限速必须在口令校验之前：否则撞库成本由本机 BCrypt 承担")
    void guardRunsBeforePasswordWork() {
        when(userMapper.selectOne(any())).thenReturn(user("ACTIVE"));
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(guard, userMapper);
        service.login("demo", PASSWORD, "1.1.1.1", "junit");
        inOrder.verify(guard).checkLogin("1.1.1.1");
        inOrder.verify(userMapper).selectOne(any());
    }

    @Test
    @DisplayName("陌生 refresh 一律拒绝，且不签发任何东西")
    void unknownRefreshRejected() {
        when(redis.hasKey(anyString())).thenReturn(false);
        when(sessionService.findByRefreshHash(anyString())).thenReturn(null);
        BizException e = expectBiz(() -> service.refresh("never-seen-before"));
        assertTrue(e.getMessage().contains("重新登录"));
        verify(sessionService, never()).record(any());
    }

    @Test
    @DisplayName("轮换后再放旧 refresh：吊销的必须是那条还活着的会话")
    void replayAfterRotationRevokesTheLiveSession() {
        // 这条用例是"先轮换、再重放"的**合成**用例，故意的。分开测会各自成立：
        // refreshRotates 只验 rotate 被调用，旧版 refreshReuseRevokesSession 把
        // findByRefreshHash 直接 stub 成返回会话——于是两条都绿，而真值里
        // rotate 是**同一行就地换新摘要**（jti 不变），重放时按旧摘要查必然查不到，
        // "重放即吊销整条会话"从头到尾没发生过。这里用一个状态化的行把那个洞逼出来。
        ConsumerSessionEntity row = liveSession("jti-live");
        java.util.concurrent.atomic.AtomicReference<String> hashInRow =
                new java.util.concurrent.atomic.AtomicReference<>();
        String originalRefresh = "refresh-token-before-rotation";
        hashInRow.set(sha256Hex(originalRefresh));

        when(sessionService.findByRefreshHash(anyString())).thenAnswer(inv ->
                inv.getArgument(0).equals(hashInRow.get()) ? row : null);
        when(sessionService.findByJti("jti-live")).thenReturn(row);
        org.mockito.Mockito.doAnswer(inv -> {
            hashInRow.set(inv.getArgument(1));   // 就地换摘要：旧摘要从此查不到任何行
            return null;
        }).when(sessionService).rotate(any(ConsumerSessionEntity.class), anyString(),
                any(LocalDateTime.class));
        when(userMapper.selectById(70001L)).thenReturn(user("ACTIVE"));
        final String usedKey = USED_PREFIX + sha256Hex(originalRefresh);
        when(redis.hasKey(usedKey)).thenReturn(false, true);
        when(valueOps.get(usedKey)).thenReturn("jti-live");

        TokenPairVO rotated = service.refresh(originalRefresh);
        assertNotNull(rotated.accessToken(), "第一次刷新应当正常发新凭证");
        assertThrows(BizException.class, () -> service.refresh(originalRefresh),
                "旧 refresh 第二次出现必须被拒");

        // 这两行就是那条承诺本身：按黑名单里的 jti 吊销，并且留下事件
        verify(sessionService).revoke(eq("jti-live"), eq("REFRESH_REUSED"), any(Duration.class));
        verify(events).record(eq("REFRESH_REUSED"), eq(70001L), eq("demo"), eq("jti-live"),
                any(), any(), eq("REVOKED"));
    }

    @Test
    @DisplayName("黑名单里没带 jti（外部写入/TTL 边界）时退回按摘要找")
    void replayFallsBackToHashLookup() {
        ConsumerSessionEntity row = liveSession("jti-by-hash");
        String token = "never-rotated-but-blacklisted";
        when(redis.hasKey(anyString())).thenReturn(true);
        when(valueOps.get(anyString())).thenReturn(null);        // 值缺失
        when(sessionService.findByRefreshHash(anyString())).thenReturn(row);

        expectBiz(() -> service.refresh(token));
        verify(sessionService).revoke(eq("jti-by-hash"), eq("REFRESH_REUSED"), any(Duration.class));
    }

    /** 一条"活着"的会话行：access 未到期、refresh 未到期、未被吊销 */
    private ConsumerSessionEntity liveSession(String jti) {
        ConsumerSessionEntity s = new ConsumerSessionEntity();
        s.setId(1L);
        s.setJti(jti);
        s.setUserId(70001L);
        s.setIdentifier("demo");
        s.setExpireAt(LocalDateTime.now().plusMinutes(10));
        s.setRefreshExpireAt(LocalDateTime.now().plusDays(30));
        return s;
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("refresh 成功轮换：旧值进黑名单、会话行被 rotate、发出新 access")
    void refreshRotates() {
        ConsumerSessionEntity s = new ConsumerSessionEntity();
        s.setId(2L);
        s.setJti("jti-rot");
        s.setUserId(70001L);
        s.setIdentifier("demo");
        s.setExpireAt(LocalDateTime.now().plusMinutes(10));
        s.setRefreshExpireAt(LocalDateTime.now().plusDays(30));

        when(redis.hasKey(anyString())).thenReturn(false);
        when(sessionService.findByRefreshHash(anyString())).thenReturn(s);
        when(userMapper.selectById(70001L)).thenReturn(user("ACTIVE"));

        TokenPairVO pair = service.refresh("a-valid-unused-refresh");
        assertNotNull(pair.accessToken());
        verify(sessionService).rotate(eq(s), anyString(), any(LocalDateTime.class));
        // 旧 refresh 的摘要必须进黑名单，TTL 不能为 0，否则等于允许它再生
        verify(valueOps).set(anyString(), eq("jti-rot"), any(Duration.class));
    }

    @Test
    @DisplayName("refresh 换出的新 access 沿用同一个 jti：吊销粒度不能因刷新而漂移")
    void refreshKeepsSameJti() {
        ConsumerSessionEntity s = new ConsumerSessionEntity();
        s.setId(3L);
        s.setJti("jti-stable");
        s.setUserId(70001L);
        s.setIdentifier("demo");
        s.setExpireAt(LocalDateTime.now().plusMinutes(10));
        s.setRefreshExpireAt(LocalDateTime.now().plusDays(30));
        when(redis.hasKey(anyString())).thenReturn(false);
        when(sessionService.findByRefreshHash(anyString())).thenReturn(s);
        when(userMapper.selectById(70001L)).thenReturn(user("ACTIVE"));

        TokenPairVO pair = service.refresh("valid-refresh");
        ConsumerVerifyResult r = new ConsumerTokenCodec(SECRET, Duration.ofSeconds(30))
                .verifyAccess(pair.accessToken(), java.time.Instant.now().getEpochSecond());
        assertEquals("jti-stable", r.claims().jti());
    }

    @Test
    @DisplayName("refresh 已到期：拒绝并吊销，不给「再刷一次」的机会")
    void expiredRefreshIsRejectedAndRevoked() {
        ConsumerSessionEntity s = new ConsumerSessionEntity();
        s.setId(4L);
        s.setJti("jti-expired-refresh");
        s.setUserId(70001L);
        s.setIdentifier("demo");
        s.setExpireAt(LocalDateTime.now().plusMinutes(10));
        s.setRefreshExpireAt(LocalDateTime.now().minusDays(1));
        when(redis.hasKey(anyString())).thenReturn(false);
        when(sessionService.findByRefreshHash(anyString())).thenReturn(s);

        expectBiz(() -> service.refresh("stale-refresh"));
        verify(sessionService).revoke(eq("jti-expired-refresh"), eq("REFRESH_EXPIRED"), any(Duration.class));
    }

    @Test
    @DisplayName("改密必须同时作废全部会话，否则被偷走的旧 token 还能跑到自然过期")
    void passwordChangeRevokesEverySession() {
        when(userMapper.selectById(70001L)).thenReturn(user("ACTIVE"));
        service.changePassword(70001L, PASSWORD, "brand-new-pass-1", "demo");
        verify(sessionService).revokeAll(70001L, "PASSWORD_CHANGED");
        verify(events).record(eq("PASSWORD_CHANGED"), eq(70001L), anyString(), any(),
                any(), any(), eq("SUCCESS"));
    }

    @Test
    @DisplayName("新口令与原口令相同要拒，且不得动会话")
    void samePasswordRejected() {
        when(userMapper.selectById(70001L)).thenReturn(user("ACTIVE"));
        BizException e = expectBiz(() ->
                service.changePassword(70001L, PASSWORD, PASSWORD, "demo"));
        assertTrue(e.getMessage().contains("不能与原口令相同"));
        verify(sessionService, never()).revokeAll(anyLong(), anyString());
    }

    @Test
    @DisplayName("注册撞名走唯一键的语义：拒，且不发凭证")
    void duplicateIdentifierRejected() {
        when(userMapper.selectOne(any())).thenReturn(user("ACTIVE"));
        BizException e = expectBiz(() -> service.register("demo", "somepass123", "x", "1.1.1.1", "junit"));
        assertTrue(e.getMessage().contains("已被使用"));
        verify(sessionService, never()).record(any());
    }

    @Test
    @DisplayName("注册即登录：直接发一对可用凭证")
    void registerIssuesSession() {
        when(userMapper.selectOne(any())).thenReturn(null);
        TokenPairVO pair = service.register("newbie", "somepass123", "新人", "1.1.1.1", "junit");
        assertEquals(ConsumerVerifyResult.Status.OK,
                new ConsumerTokenCodec(SECRET, Duration.ofSeconds(30))
                        .verifyAccess(pair.accessToken(), java.time.Instant.now().getEpochSecond()).status());
        verify(events).record(eq("REGISTER"), any(), eq("newbie"), any(),
                eq("1.1.1.1"), eq("junit"), eq("SUCCESS"));
    }
}
