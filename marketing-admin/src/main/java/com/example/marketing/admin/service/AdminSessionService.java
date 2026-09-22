package com.example.marketing.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.marketing.admin.config.AdminProperties;
import com.example.marketing.admin.infrastructure.entity.AdminSessionEntity;
import com.example.marketing.admin.infrastructure.mapper.AdminSessionMapper;
import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 会话状态：DB 为准，Redis 只放两把"网关每次请求都要问"的键。
 *
 * <ul>
 *   <li>{@code admin:revoked:{jti}} —— 单个会话吊销位，TTL = 该 token 剩余寿命，
 *       过期即自动消失（键留着没有意义，token 本来也失效了）；</li>
 *   <li>{@code admin:user:bump:{userId}} —— 整号作废时刻，改密/停用一次杀光所有会话，
 *       不必遍历会话表；{@code iat} 早于它的 token 一律拒。</li>
 * </ul>
 *
 * <p>没有第三个 {@code admin:session:{jti}} 键：设计稿里它是"在线列表快路径"，
 * 但在线列表是后台翻页的低 QPS 读，走 DB 索引才对；Redis 里再存一份只是多一个
 * 会和 DB 不同步的真相源。吊销判定由上面两把键覆盖，不需要它。</p>
 *
 * <p>Redis 整体被清空时的后果是"已吊销的 token 重新可用直到自然过期"，
 * 上限是 accessTtl（默认 900s）—— 这是选 Redis 做吊销位的已知代价，
 * 换 DB 判定会给每个请求加一次查询。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSessionService {

    public static final String REVOKED_PREFIX = "admin:revoked:";
    public static final String BUMP_PREFIX = "admin:user:bump:";

    private final AdminSessionMapper sessionMapper;
    private final StringRedisTemplate redis;
    private final AdminProperties properties;

    /** 落一条会话。Redis 里没有对应键，见类注释。 */
    public void record(AdminSessionEntity session) {
        sessionMapper.insert(session);
    }

    /**
     * 按 accessTtl 上界吊销。经网关时后端拿不到 token 的 exp（网关只转发身份，
     * 不转发它验过的字段），所以宁可多留一会儿：这把键的语义是"这个 jti 别再信了"，
     * 留久一点只有好处。
     */
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
     * 作废某个账号的全部会话：改密、停用、管理员强制下线共用这一条。
     * 先写 bump 键再改库 —— 反过来的话中间窗口里旧 token 仍能通过网关。
     */
    public void revokeAll(long userId, String reason) {
        long nowEpoch = LocalDateTime.now().atZone(ZoneId.systemDefault()).toEpochSecond();
        Duration bumpTtl = Duration.ofSeconds(properties.getAccessTtlSeconds()
                + 2 * properties.getClockSkewSeconds());
        redis.opsForValue().set(BUMP_PREFIX + userId, String.valueOf(nowEpoch), bumpTtl);

        List<AdminSessionEntity> active = activeSessions(userId);
        for (AdminSessionEntity session : active) {
            Duration remaining = Duration.between(LocalDateTime.now(), session.getExpireAt());
            markRevoked(session.getJti(), reason);
            if (!remaining.isNegative() && !remaining.isZero()) {
                redis.opsForValue().set(REVOKED_PREFIX + session.getJti(), reason, remaining);
            }
        }
        log.info("[admin] 会话批量作废 userId={}, count={}, reason={}", userId, active.size(), reason);
    }

    /** 在线会话（DB 口径：未吊销且未到期），翻页给运维看 */
    public PageResult<AdminSessionEntity> listOnline(PageQuery query, Long userId) {
        var wrapper = Wrappers.<AdminSessionEntity>lambdaQuery()
                .isNull(AdminSessionEntity::getRevokedAt)
                .gt(AdminSessionEntity::getExpireAt, LocalDateTime.now())
                .eq(userId != null, AdminSessionEntity::getUserId, userId)
                .orderByDesc(AdminSessionEntity::getId);
        Page<AdminSessionEntity> page = sessionMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);
        return PageResult.of(page.getTotal(), query.getPage(), query.getSize(), page.getRecords());
    }

    public boolean isRevoked(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(REVOKED_PREFIX + jti));
    }

    /** 整号作废时刻（epoch 秒），无键表示从未发生过 */
    public Long bumpedAt(long userId) {
        String value = redis.opsForValue().get(BUMP_PREFIX + userId);
        return value == null ? null : Long.parseLong(value);
    }

    private List<AdminSessionEntity> activeSessions(long userId) {
        return sessionMapper.selectList(Wrappers.<AdminSessionEntity>lambdaQuery()
                .eq(AdminSessionEntity::getUserId, userId)
                .isNull(AdminSessionEntity::getRevokedAt)
                .gt(AdminSessionEntity::getExpireAt, LocalDateTime.now()));
    }

    private void markRevoked(String jti, String reason) {
        AdminSessionEntity patch = new AdminSessionEntity();
        patch.setRevokeReason(reason);
        patch.setRevokedAt(LocalDateTime.now());
        int updated = sessionMapper.update(patch, Wrappers.<AdminSessionEntity>lambdaUpdate()
                .eq(AdminSessionEntity::getJti, jti)
                .isNull(AdminSessionEntity::getRevokedAt));
        if (updated == 0) {
            log.info("[admin] 吊销时会话已不在有效态 jti={}, reason={}", jti, reason);
        }
    }
}
