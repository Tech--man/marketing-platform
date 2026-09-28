package com.example.marketing.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.marketing.account.config.AccountProperties;
import com.example.marketing.account.dto.ConsumerMeVO;
import com.example.marketing.account.dto.ConsumerSessionVO;
import com.example.marketing.account.dto.TokenPairVO;
import com.example.marketing.account.infrastructure.entity.ConsumerSessionEntity;
import com.example.marketing.account.infrastructure.entity.ConsumerUserEntity;
import com.example.marketing.account.infrastructure.mapper.ConsumerUserMapper;
import com.example.marketing.account.security.ConsumerLoginGuard;
import com.example.marketing.account.security.ConsumerLoginPolicy;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.security.ConsumerClaims;
import com.example.marketing.common.security.ConsumerTokenCodec;
import com.example.marketing.common.security.ConsumerVerifyResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 消费者账号的用例编排：注册、登录、刷新、登出、改密、我是谁。
 *
 * <p>三处与后台同源、但值得在代码里点明的判断：</p>
 * <ol>
 *   <li><b>口令不存在的账号也要花一样的时间</b>：查不到用户时去比一枚固定占位哈希，
 *       否则"响应快的那个用户名没注册"就成了免费的枚举接口；</li>
 *   <li><b>限速在 BCrypt 之前</b>：BCrypt 单次 50-100ms 且吃 CPU，先算口令再决定理不理
 *       等于把拒绝服务的成本送给攻击者 —— 他要的正是这个；</li>
 *   <li><b>改密必然作废全部会话</b>：只改哈希不 bump，被偷走的旧 token 还能跑到自然过期，
 *       而"改密"这个动作在用户心里等于"我把门锁换了"。</li>
 * </ol>
 *
 * <p>refresh 采用<b>一次性轮换</b>：每次刷新换一枚新 refresh，旧值的摘要进 Redis 黑名单
 * 留到它原本的过期时刻。已用过的 refresh 再次出现 → 判定为泄露，直接吊销整条会话。
 * 不做轮换的 refresh 等于一枚 30 天有效、丢了还能一直用的 bearer 凭证。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsumerAuthService {

    private static final String REFRESH_USED_PREFIX = "consumer:refresh:used:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConsumerUserMapper userMapper;
    private final ConsumerSessionService sessionService;
    private final ConsumerEventService events;
    private final ConsumerLoginGuard guard;
    private final ConsumerTokenCodec codec;
    private final PasswordEncoder passwordEncoder;
    private final AccountProperties properties;
    private final StringRedisTemplate redis;

    /** 账号不存在时用来消耗等量 CPU 的固定哈希（$2a$10$ + 53 个 '.' 是合法 BCrypt 格式） */
    private static final String PAD_HASH = "$2a$10$........................................";

    @Transactional
    public TokenPairVO register(String identifier, String rawPassword, String nickname, String ip, String userAgent) {
        guard.checkRegister(ip);
        String id = normalize(identifier);
        if (findByIdentifier(id) != null) {
            // 先查再插不是竞态防护（那靠 uk_identifier），是为了给出人话错误而不是 500
            throw BizException.of(ErrorCode.BAD_REQUEST, "该登录名已被使用");
        }
        LocalDateTime now = LocalDateTime.now();
        ConsumerUserEntity user = new ConsumerUserEntity();
        user.setIdentifier(id);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setNickname(nickname == null || nickname.isBlank() ? defaultNickname(id) : nickname.trim());
        user.setStatus(ConsumerLoginPolicy.STATUS_ACTIVE);
        user.setPwdVersion(1);
        user.setFailCount(0);
        user.setCreateTime(now);
        user.setUpdateTime(now);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发注册的真正落点：唯一键才是判定，先查只是体验
            throw BizException.of(ErrorCode.BAD_REQUEST, "该登录名已被使用");
        }
        events.record("REGISTER", user.getId(), id, null, ip, userAgent, "SUCCESS");
        log.info("[account] 注册成功 uid={}, identifier={}, ip={}", user.getId(), id, ip);
        // 注册即登录：让消费者少一次口令输入，也让"注册完看不到自己的券"这类困惑不成立
        return issueSession(user, ip, userAgent, "REGISTER");
    }

    public TokenPairVO login(String identifier, String rawPassword, String ip, String userAgent) {
        // 限速必须在 BCrypt 之前，理由见类注释第 2 点
        guard.checkLogin(ip);
        String id = normalize(identifier);
        ConsumerUserEntity user = findByIdentifier(id);
        boolean matches = passwordEncoder.matches(rawPassword, user == null ? PAD_HASH : user.getPasswordHash());
        ConsumerLoginPolicy.Verdict verdict =
                ConsumerLoginPolicy.evaluate(user, matches, LocalDateTime.now());

        if (verdict != ConsumerLoginPolicy.Verdict.PASS) {
            onFailure(user, verdict, id, ip, userAgent);
        }
        userMapper.update(null, Wrappers.<ConsumerUserEntity>lambdaUpdate()
                .eq(ConsumerUserEntity::getId, user.getId())
                .set(ConsumerUserEntity::getFailCount, 0)
                .set(ConsumerUserEntity::getLockUntil, null)
                .set(ConsumerUserEntity::getLastLoginTime, LocalDateTime.now()));
        events.record("LOGIN", user.getId(), id, null, ip, userAgent, "SUCCESS");
        return issueSession(user, ip, userAgent, "LOGIN");
    }

    /**
     * 用 refresh 换一对新凭证。refresh token 不是访问凭证：它只能打这一个端点。
     */
    @Transactional
    public TokenPairVO refresh(String refreshToken) {
        String hash = sha256(refreshToken);
        // 已用过的 refresh 再次出现 = 要么被重放、要么被中间人抄了一份。
        // 两种情况都不该继续发新凭证，直接把这条会话整体吊销。
        if (Boolean.TRUE.equals(redis.hasKey(REFRESH_USED_PREFIX + hash))) {
            // 找"受害的是哪条会话"必须按黑名单 value 里那个 jti 找，**不能**按 refresh 摘要找：
            // rotate() 是同一行就地换新摘要（jti 不变），所以轮换过一次之后，旧摘要在表里
            // 已经不存在了 —— 按摘要查会拿到 null，这段就会安静地什么都不吊销，只抛一个 40100，
            // 而"重放即吊销整条会话"这条承诺只剩注释。findByRefreshHash 退到兜底位：
            // 只服务于"黑名单里有值但值缺失"（TTL 边界/外部写入）这种查得回摘要的残余形状。
            String pinnedJti = redis.opsForValue().get(REFRESH_USED_PREFIX + hash);
            ConsumerSessionEntity victim = pinnedJti != null && !pinnedJti.isBlank()
                    ? sessionService.findByJti(pinnedJti)
                    : sessionService.findByRefreshHash(hash);
            if (victim != null) {
                sessionService.revoke(victim.getJti(), "REFRESH_REUSED",
                        remaining(victim.getExpireAt()));
                events.record("REFRESH_REUSED", victim.getUserId(), victim.getIdentifier(),
                        victim.getJti(), null, null, "REVOKED");
                log.warn("[account] refresh 重放，会话已吊销 uid={}, jti={}",
                        victim.getUserId(), victim.getJti());
            } else {
                // 吊销不了也要留痕：静默跳过正是上面那条 bug 的藏身处
                log.warn("[account] refresh 重放但定位不到受害会话，未吊销 hash 前 8 位={}",
                        hash.substring(0, 8));
            }
            throw BizException.of(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }

        ConsumerSessionEntity session = sessionService.findByRefreshHash(hash);
        if (session == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }
        if (session.getRevokedAt() != null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }
        if (session.getRefreshExpireAt() != null
                && session.getRefreshExpireAt().isBefore(LocalDateTime.now())) {
            sessionService.revoke(session.getJti(), "REFRESH_EXPIRED", Duration.ofSeconds(1));
            throw BizException.of(ErrorCode.TOKEN_EXPIRED, "登录已过期，请重新登录");
        }
        ConsumerUserEntity user = userMapper.selectById(session.getUserId());
        if (user == null || !ConsumerLoginPolicy.STATUS_ACTIVE.equals(user.getStatus())) {
            sessionService.revoke(session.getJti(), "DISABLED", remaining(session.getExpireAt()));
            throw BizException.of(ErrorCode.FORBIDDEN, "账号不可用");
        }

        // 轮换：旧值进黑名单，TTL 给到它原本的寿命 —— 黑名单留短了等于允许旧 refresh 再生
        Duration oldLife = remaining(session.getRefreshExpireAt());
        if (!oldLife.isZero() && !oldLife.isNegative()) {
            redis.opsForValue().set(REFRESH_USED_PREFIX + hash, session.getJti(), oldLife);
        }
        TokenPairVO pair = rotateSession(user, session);
        events.record("REFRESH", user.getId(), user.getIdentifier(), session.getJti(),
                null, null, "SUCCESS");
        return pair;
    }

    public void logout(long uid, String jti, String identifier) {
        sessionService.revoke(jti, "LOGOUT", remainingOfAccess());
        events.record("LOGOUT", uid, identifier, jti, null, null, "SUCCESS");
    }

    /**
     * 改密。先 bump 再落哈希的顺序由 {@link ConsumerSessionService#revokeAll} 内部保证；
     * 这里要额外做的是 pwd_version+1 —— 它是"这个号曾经的所有 token 都作废"的记账位，
     * 即便 bump 键因 Redis 清空而丢失，DB 上仍留得下这条事实。
     */
    @Transactional
    public void changePassword(long uid, String oldPassword, String newPassword, String identifier) {
        ConsumerUserEntity user = userMapper.selectById(uid);
        if (user == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "账号不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "原口令不正确");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "新口令不能与原口令相同");
        }
        userMapper.update(null, Wrappers.<ConsumerUserEntity>lambdaUpdate()
                .eq(ConsumerUserEntity::getId, uid)
                .set(ConsumerUserEntity::getPasswordHash, passwordEncoder.encode(newPassword))
                .set(ConsumerUserEntity::getPwdVersion, user.getPwdVersion() + 1));
        sessionService.revokeAll(uid, "PASSWORD_CHANGED");
        events.record("PASSWORD_CHANGED", uid, identifier, null, null, null, "SUCCESS");
        log.info("[account] 改密并作废全部会话 uid={}", uid);
    }

    public ConsumerMeVO me(long uid) {
        ConsumerUserEntity user = userMapper.selectById(uid);
        if (user == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "账号不存在");
        }
        return new ConsumerMeVO(user.getId(), user.getIdentifier(), user.getNickname(), user.getStatus());
    }

    public List<ConsumerSessionVO> sessions(long uid, String currentJti) {
        return sessionService.activeSessions(uid).stream()
                .map(s -> new ConsumerSessionVO(s.getJti(), s.getLoginIp(), s.getUserAgent(),
                        s.getCreateTime(), s.getExpireAt(), s.getJti().equals(currentJti)))
                .toList();
    }

    /** 供网关侧回查吊销位；网关自己读 Redis，这里只是把键名收在一处 */
    public boolean isRevoked(String jti) {
        return sessionService.isRevoked(jti);
    }

    public ConsumerVerifyResult verifyAccess(String token) {
        return codec.verifyAccess(token, Instant.now().getEpochSecond());
    }

    // ---------- 内部 ----------

    private TokenPairVO issueSession(ConsumerUserEntity user, String ip, String userAgent, String scene) {
        String jti = UUID.randomUUID().toString().replace("-", "");
        String refresh = newRefreshToken();
        long now = Instant.now().getEpochSecond();
        long accessExp = now + properties.getAccessTtlSeconds();

        ConsumerSessionEntity session = new ConsumerSessionEntity();
        session.setJti(jti);
        session.setUserId(user.getId());
        session.setIdentifier(user.getIdentifier());
        session.setRefreshHash(sha256(refresh));
        session.setLoginIp(ip);
        session.setUserAgent(truncate(userAgent, 256));
        session.setExpireAt(LocalDateTime.ofInstant(Instant.ofEpochSecond(accessExp),
                java.time.ZoneId.systemDefault()));
        session.setRefreshExpireAt(LocalDateTime.now().plusSeconds(properties.getRefreshTtlSeconds()));
        session.setCreateTime(LocalDateTime.now());
        sessionService.record(session);

        String access = codec.issue(new ConsumerClaims(user.getId(), user.getIdentifier(), jti,
                ConsumerClaims.TYPE_ACCESS, now, accessExp));
        log.info("[account] 签发会话 scene={}, uid={}, jti={}", scene, user.getId(), jti);
        return new TokenPairVO(access, refresh, properties.getAccessTtlSeconds(),
                user.getId(), user.getIdentifier(), user.getNickname());
    }

    /** 轮换沿用同一条会话行与同一个 jti，只换 refresh；access 重新签一枚 */
    private TokenPairVO rotateSession(ConsumerUserEntity user, ConsumerSessionEntity session) {
        String refresh = newRefreshToken();
        long now = Instant.now().getEpochSecond();
        long accessExp = now + properties.getAccessTtlSeconds();
        sessionService.rotate(session, sha256(refresh),
                LocalDateTime.now().plusSeconds(properties.getRefreshTtlSeconds()));
        String access = codec.issue(new ConsumerClaims(user.getId(), user.getIdentifier(),
                session.getJti(), ConsumerClaims.TYPE_ACCESS, now, accessExp));
        return new TokenPairVO(access, refresh, properties.getAccessTtlSeconds(),
                user.getId(), user.getIdentifier(), user.getNickname());
    }

    /**
     * 失败记账。三种拒绝各记一条事件（运维要能区分"口令错"与"账号被停用"），
     * 但对外一律回同一句人话 —— 别把差异告诉攻击者。
     */
    private void onFailure(ConsumerUserEntity user, ConsumerLoginPolicy.Verdict verdict,
                           String identifier, String ip, String userAgent) {
        switch (verdict) {
            case DISABLED -> {
                events.record("LOGIN_FAILED", user == null ? null : user.getId(), identifier,
                        null, ip, userAgent, "DISABLED");
                throw BizException.of(ErrorCode.FORBIDDEN, "账号或口令不正确");
            }
            case LOCKED -> {
                events.record("LOGIN_FAILED", user.getId(), identifier, null, ip, userAgent, "LOCKED");
                throw BizException.of(ErrorCode.FORBIDDEN, "账号或口令不正确");
            }
            default -> {
                if (user != null) {
                    ConsumerLoginPolicy.FailureState state = ConsumerLoginPolicy.onBadCredentials(
                            user, properties.getMaxFailCount(), properties.getLockMinutes(),
                            LocalDateTime.now());
                    userMapper.update(null, Wrappers.<ConsumerUserEntity>lambdaUpdate()
                            .eq(ConsumerUserEntity::getId, user.getId())
                            .set(ConsumerUserEntity::getFailCount, state.failCount())
                            .set(ConsumerUserEntity::getLockUntil, state.lockUntil()));
                    if (state.lockUntil() != null && user.getLockUntil() == null) {
                        log.warn("[account] 连续失败达阈值已锁定 uid={}, until={}",
                                user.getId(), state.lockUntil());
                    }
                }
                events.record("LOGIN_FAILED", user == null ? null : user.getId(), identifier,
                        null, ip, userAgent, "BAD_CREDENTIALS");
                throw BizException.of(ErrorCode.UNAUTHORIZED, "账号或口令不正确");
            }
        }
    }

    private ConsumerUserEntity findByIdentifier(String identifier) {
        return userMapper.selectOne(Wrappers.<ConsumerUserEntity>lambdaQuery()
                .eq(ConsumerUserEntity::getIdentifier, identifier));
    }

    private static String normalize(String identifier) {
        return identifier == null ? "" : identifier.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String defaultNickname(String identifier) {
        int at = identifier.indexOf('@');
        return at > 0 ? identifier.substring(0, at) : identifier;
    }

    private static String newRefreshToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private Duration remainingOfAccess() {
        return Duration.ofSeconds(properties.getAccessTtlSeconds());
    }

    private static Duration remaining(LocalDateTime until) {
        if (until == null) {
            return Duration.ofSeconds(1);
        }
        Duration d = Duration.between(LocalDateTime.now(), until);
        return d.isNegative() || d.isZero() ? Duration.ofSeconds(1) : d;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
