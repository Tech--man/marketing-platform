package com.example.marketing.account.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消费者会话。一行 = 一次登录，同时承载 access 的 jti 与配对的 refresh token。
 *
 * <p>refresh 存的是 <b>SHA-256 摘要</b>而不是明文（列名 {@code refresh_hash}）：
 * 这张表一旦被读走（备份、从库、报表），明文 refresh token 等于 30 天的完整账号访问权。
 * access 是短寿命且可从 Redis 吊销，才允许只存 jti。</p>
 *
 * <p>轮换（rotation）：每次用 refresh 换新对，就把本行 {@code refresh_hash} 覆盖成新值、
 * {@code rotatedAt} 打点。旧 refresh 因此第二次使用即失配 —— 那正是"refresh 已泄露"的信号，
 * 命中时直接吊销整条会话而不是放行。</p>
 */
@Data
@TableName("consumer_session")
public class ConsumerSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** access token 的 jti，吊销按它拉黑 */
    private String jti;
    private Long userId;
    private String identifier;
    /** refresh token 的 SHA-256（hex），不落明文 */
    private String refreshHash;
    private String loginIp;
    private String userAgent;
    private LocalDateTime expireAt;
    /** refresh 自身的过期时刻；到点必须重新登录 */
    private LocalDateTime refreshExpireAt;
    /** 最近一次轮换时刻；null = 从未轮换 */
    private LocalDateTime rotatedAt;
    /** PASSWORD_CHANGED / DISABLED / FORCE_LOGOUT / LOGOUT / REFRESH_REUSED，null = 仍有效 */
    private String revokeReason;
    private LocalDateTime revokedAt;
    private LocalDateTime createTime;
}
