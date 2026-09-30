package com.example.marketing.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.marketing.admin.config.AdminProperties;
import com.example.marketing.admin.audit.AuditRecord;
import com.example.marketing.admin.dto.LoginView;
import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.admin.security.LoginGuard;
import com.example.marketing.admin.infrastructure.entity.AdminSessionEntity;
import com.example.marketing.admin.infrastructure.entity.AdminUserEntity;
import com.example.marketing.admin.infrastructure.mapper.AdminUserMapper;
import com.example.marketing.admin.audit.AuditService;
import com.example.marketing.admin.security.LoginPolicy;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.security.AdminClaims;
import com.example.marketing.common.security.AdminTokenCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 登录、登出、改密与强制下线的编排。判定本身在 {@link LoginPolicy}（纯函数，已被单测钉住），
 * 这里只做"读库 → 判定 → 记账 → 签发"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    /**
     * 账号不存在时也要跑一次 BCrypt。不这么做的话"用户名不存在"是瞬时返回、
     * "用户名存在但口令错"要 50-100ms —— 登录口就成了一个用户名枚举接口，
     * 而且是最省事的那种（不用看响应体，掐表就行）。
     */
    private static final String TIMING_PAD_HASH =
            "$2a$10$o1dcGQ41rEUu7qCLu68A/uftVufxdN6jObAgQtcyea5eU8NqykrYC";

    private final AdminUserMapper userMapper;
    private final AdminSessionService sessionService;
    private final AdminTokenCodec codec;
    private final PasswordEncoder passwordEncoder;
    private final AdminProperties properties;
    private final LoginGuard loginGuard;
    private final AuditService auditService;

    public LoginView login(String username, String rawPassword, String ip, String userAgent) {
        long startedAt = System.nanoTime();
        // 限速在 BCrypt 之前：这一层的目的是别让撞库把 CPU 吃满，验完再限就白花了那 50-100ms
        loginGuard.check(ip);
        LocalDateTime now = LocalDateTime.now();
        AdminUserEntity user = findByName(username);
        boolean matches = passwordEncoder.matches(rawPassword,
                user == null ? TIMING_PAD_HASH : user.getPasswordHash());

        LoginPolicy.Verdict verdict = LoginPolicy.evaluate(user, matches, now);
        if (verdict != LoginPolicy.Verdict.PASS) {
            reject(user, username, verdict, now, ip, startedAt);
        }

        AdminUserEntity target = user;
        long issuedAt = Instant.now().getEpochSecond();
        long expiresAt = issuedAt + properties.getAccessTtlSeconds();
        String jti = UUID.randomUUID().toString().replace("-", "");
        String token = codec.issue(new AdminClaims(target.getId(), target.getUsername(), target.getRole(),
                target.getPwdVersion(), jti, issuedAt, expiresAt));

        sessionService.record(session(target, jti,
                now.plusSeconds(properties.getAccessTtlSeconds()), ip, userAgent));
        clearFailures(target.getId());
        log.info("[admin] 登录成功 username={}, jti={}, ip={}", target.getUsername(), jti, ip);
        auditService.record(new AuditRecord(target.getId(), target.getUsername(), target.getRole(),
                "login.success", "session", jti, "POST", "/api/admin/auth/login",
                "username=" + target.getUsername(), 0, "", ip, costMs(startedAt)));
        return new LoginView(token, "Bearer", properties.getAccessTtlSeconds(), target.getId(),
                target.getUsername(), target.getDisplayName(), target.getRole());
    }

    /** 主动登出：只作废当前这一个会话，同账号其他设备不动 */
    public void logout(AdminPrincipal actor, String reason) {
        sessionService.revoke(actor.jti(), reason);
        auditService.record(AuditRecord.ofAction(actor.uid(), actor.username(), actor.role(),
                "logout", "session", actor.jti(), ""));
        log.info("[admin] 登出 username={}, jti={}, reason={}", actor.username(), actor.jti(), reason);
    }

    public void changePassword(AdminPrincipal actor, String oldPassword, String newPassword) {
        AdminUserEntity user = userMapper.selectById(actor.uid());
        if (user == null) {
            throw BizException.of(ErrorCode.SESSION_REVOKED, "账号已不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "旧口令错误");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "新口令不能与当前口令相同");
        }
        userMapper.update(null, Wrappers.<AdminUserEntity>lambdaUpdate()
                .eq(AdminUserEntity::getId, user.getId())
                .set(AdminUserEntity::getPasswordHash, passwordEncoder.encode(newPassword))
                .set(AdminUserEntity::getPwdVersion, user.getPwdVersion() + 1));
        // 改密必须把全部会话踢掉：只踢当前会话的话，被盗的旧 token 在新口令生效后还能用 15 分钟
        sessionService.revokeAll(user.getId(), "PASSWORD_CHANGED");
        auditService.record(AuditRecord.ofAction(user.getId(), user.getUsername(), user.getRole(),
                "password.change", "user", String.valueOf(user.getId()), ""));
        log.info("[admin] 改密成功并已作废全部会话 username={}", user.getUsername());
    }

    private void reject(AdminUserEntity user, String username, LoginPolicy.Verdict verdict,
                        LocalDateTime now, String ip, long startedAt) {
        // W3.8（2026-09-30 第二轮复审）：三态对外同话术——DISABLED/LOCKED 的专属文案
        // 等于告诉探测者"这个用户名存在且状态非 ACTIVE"（账号枚举面）。差异只进审计
        // （login.disabled / login.locked / login.bad_credentials 三条 action 运维可辨）。
        switch (verdict) {
            case DISABLED -> {
                audit(user, username, "login.disabled", ip, startedAt);
                throw BizException.of(ErrorCode.UNAUTHORIZED, "用户名或口令错误");
            }
            case LOCKED -> {
                audit(user, username, "login.locked", ip, startedAt);
                throw BizException.of(ErrorCode.UNAUTHORIZED, "用户名或口令错误");
            }
            default -> {
                if (user != null) {
                    LoginPolicy.FailureState state = LoginPolicy.onBadCredentials(user,
                            properties.getMaxFailCount(), properties.getLockMinutes(), now);
                    userMapper.update(null, Wrappers.<AdminUserEntity>lambdaUpdate()
                            .eq(AdminUserEntity::getId, user.getId())
                            .set(AdminUserEntity::getFailCount, state.failCount())
                            .set(AdminUserEntity::getLockUntil, state.lockUntil()));
                    if (state.lockUntil() != null) {
                        log.warn("[admin] 连续失败达阈值已锁定 username={}, 解锁于 {}",
                                user.getUsername(), state.lockUntil());
                    }
                }
                audit(user, username, "login.bad_credentials", ip, startedAt);
                throw BizException.of(ErrorCode.UNAUTHORIZED, "用户名或口令错误");
            }
        }
    }

    private void clearFailures(long userId) {
        userMapper.update(null, Wrappers.<AdminUserEntity>lambdaUpdate()
                .eq(AdminUserEntity::getId, userId)
                .set(AdminUserEntity::getFailCount, 0)
                .set(AdminUserEntity::getLockUntil, null)
                .set(AdminUserEntity::getLastLoginTime, LocalDateTime.now()));
    }

    /** 失败也要留痕：撞库的签名就是同一个 IP 上的一串 login.bad_credentials */
    private void audit(AdminUserEntity user, String username, String action, String ip, long startedAt) {
        auditService.record(new AuditRecord(user == null ? null : user.getId(),
                username == null ? "" : username, user == null ? "" : user.getRole(),
                action, "user", user == null ? "" : String.valueOf(user.getId()),
                "POST", "/api/admin/auth/login", "username=" + username,
                ErrorCode.UNAUTHORIZED.getCode(), "", ip, costMs(startedAt)));
    }

    private static long costMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private AdminUserEntity findByName(String username) {
        return userMapper.selectOne(Wrappers.<AdminUserEntity>lambdaQuery()
                .eq(AdminUserEntity::getUsername, username));
    }

    private static AdminSessionEntity session(AdminUserEntity user, String jti,
                                              LocalDateTime expireAt, String ip, String userAgent) {
        AdminSessionEntity session = new AdminSessionEntity();
        session.setJti(jti);
        session.setUserId(user.getId());
        session.setUsername(user.getUsername());
        session.setLoginIp(ip == null ? "" : ip);
        session.setUserAgent(userAgent == null ? "" : truncate(userAgent));
        session.setExpireAt(expireAt);
        return session;
    }

    private static String truncate(String value) {
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
