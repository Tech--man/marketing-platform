package com.example.marketing.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.marketing.account.config.AccountProperties;
import com.example.marketing.account.infrastructure.entity.ConsumerSessionEntity;
import com.example.marketing.account.infrastructure.mapper.ConsumerSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 消费者会话状态：DB 为准，Redis 只放两把"网关每次请求都要问"的键。
 *
 * <ul>
 *   <li>{@code consumer:revoked:{jti}} —— 单个会话吊销位，TTL = access 剩余寿命；</li>
 *   <li>{@code consumer:bump:{uid}} —— 整号作废时刻，改密/停用一次杀光所有会话，
 *       不必遍历会话表；{@code iat} 早于它的 token 一律拒。</li>
 * </ul>
 *
 * <p>键名前缀刻意与后台的 {@code admin:*} 分开：两把体系共用一个 Redis 实例
 * （LITE 就是这样），撞上前缀等于把后台会话当成消费者会话吊销。</p>
 *
 * <p>与后台同形的已知代价，不靠再加一层机制掩盖：Redis 整体被清空时的后果是
 * "已吊销的 token 重新可用直到自然过期"，上界是 accessTtl（默认 900s）。
 * 这也是 refresh token 只用来换 access、绝不直接访问业务的原因 ——
 * 长寿命凭证的吊销窗口问题，用"长寿命凭证不参与每次请求的验签路径"来解，
 * 而不是给每个请求加一次库查询。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsumerSessionService {

    public static final String REVOKED_PREFIX = "consumer:revoked:";
    public static final String BUMP_PREFIX = "consumer:bump:";

    private final ConsumerSessionMapper sessionMapper;
    private final StringRedisTemplate redis;
    private final AccountProperties properties;

    public void record(ConsumerSessionEntity session) {
        sessionMapper.insert(session);
        trimToQuota(session.getUserId());
    }

    /**
     * 超出会话配额时淘汰最旧的。按 accessTtl 上界吊销 —— 经网关时后端拿不到 token 的
     * exp（网关只转发身份），宁可多留一会儿：这把键的语义是"这个 jti 别再信了"。
     */
    private void trimToQuota(long userId) {
        int max = properties.getMaxActiveSessions();
        if (max <= 0) {
            return;
        }
        List<ConsumerSessionEntity> active = activeSessions(userId);
        for (int i = max; i < active.size(); i++) {
            ConsumerSessionEntity stale = active.get(i);
            revoke(stale.getJti(), "SESSION_QUOTA", Duration.ofSeconds(properties.getAccessTtlSeconds()));
            log.info("[account] 会话超配额已淘汰 userId={}, jti={}, keep={}", userId, stale.getJti(), max);
        }
    }

    public void revoke(String jti, String reason) {
        revoke(jti, reason, Duration.ofSeconds(properties.getAccessTtlSeconds()));
    }

    /** 吊销单个会话；ttl 由调用方按 token 剩余寿命给出 */
    public void revoke(String jti, String reason, Duration ttl) {
        markRevoked(jti, reason);
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        redis.opsForValue().set(REVOKED_PREFIX + jti, reason, ttl);
    }

    /**
     * 作废某个账号的全部会话：改密、停用、强制下线共用这一条。
     * <b>先写 bump 键再改库</b> —— 反过来的话中间窗口里旧 token 仍能通过网关。
     */
    public void revokeAll(long userId, String reason) {
        long nowEpoch = LocalDateTime.now().atZone(ZoneId.systemDefault()).toEpochSecond();
        Duration bumpTtl = Duration.ofSeconds(properties.getAccessTtlSeconds()
                + 2 * properties.getClockSkewSeconds());
        redis.opsForValue().set(BUMP_PREFIX + userId, String.valueOf(nowEpoch), bumpTtl);

        List<ConsumerSessionEntity> active = activeSessions(userId);
        for (ConsumerSessionEntity session : active) {
            Duration remaining = Duration.between(LocalDateTime.now(), session.getExpireAt());
            markRevoked(session.getJti(), reason);
            if (!remaining.isNegative() && !remaining.isZero()) {
                redis.opsForValue().set(REVOKED_PREFIX + session.getJti(), reason, remaining);
            }
        }
        log.info("[account] 会话批量作废 userId={}, count={}, reason={}", userId, active.size(), reason);
    }

    public boolean isRevoked(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(REVOKED_PREFIX + jti));
    }

    /** 整号作废时刻（epoch 秒），无键表示从未发生过 */
    public Long bumpedAt(long userId) {
        String value = redis.opsForValue().get(BUMP_PREFIX + userId);
        return value == null ? null : Long.parseLong(value);
    }

    public ConsumerSessionEntity findByJti(String jti) {
        return sessionMapper.selectOne(Wrappers.<ConsumerSessionEntity>lambdaQuery()
                .eq(ConsumerSessionEntity::getJti, jti));
    }

    public ConsumerSessionEntity findByRefreshHash(String refreshHash) {
        return sessionMapper.selectOne(Wrappers.<ConsumerSessionEntity>lambdaQuery()
                .eq(ConsumerSessionEntity::getRefreshHash, refreshHash));
    }

    /** 有效会话（未吊销且 access 未到期），按最近登录倒序 */
    public List<ConsumerSessionEntity> activeSessions(long userId) {
        return sessionMapper.selectList(Wrappers.<ConsumerSessionEntity>lambdaQuery()
                .eq(ConsumerSessionEntity::getUserId, userId)
                .isNull(ConsumerSessionEntity::getRevokedAt)
                .gt(ConsumerSessionEntity::getExpireAt, LocalDateTime.now())
                .orderByDesc(ConsumerSessionEntity::getId));
    }

    /** 轮换：把本行的 refresh 换成新摘要并打点。调用方负责在同一事务里签发新 access */
    public void rotate(ConsumerSessionEntity session, String newRefreshHash, LocalDateTime refreshExpireAt) {
        ConsumerSessionEntity patch = new ConsumerSessionEntity();
        patch.setRefreshHash(newRefreshHash);
        patch.setRotatedAt(LocalDateTime.now());
        patch.setRefreshExpireAt(refreshExpireAt);
        sessionMapper.update(patch, Wrappers.<ConsumerSessionEntity>lambdaUpdate()
                .eq(ConsumerSessionEntity::getId, session.getId()));
    }

    private void markRevoked(String jti, String reason) {
        ConsumerSessionEntity patch = new ConsumerSessionEntity();
        patch.setRevokeReason(reason);
        patch.setRevokedAt(LocalDateTime.now());
        int updated = sessionMapper.update(patch, Wrappers.<ConsumerSessionEntity>lambdaUpdate()
                .eq(ConsumerSessionEntity::getJti, jti)
                .isNull(ConsumerSessionEntity::getRevokedAt));
        if (updated == 0) {
            log.info("[account] 吊销时会话已不在有效态 jti={}, reason={}", jti, reason);
        }
    }
}
