package com.example.marketing.admin.security;

import com.example.marketing.admin.infrastructure.entity.AdminUserEntity;

import java.time.LocalDateTime;

/**
 * 登录判定的唯一出处：把"能不能进"从 DB/Redis 里剥出来做成纯函数，边界情况全部由
 * LoginPolicyTest 钉住。
 */
public final class LoginPolicy {

    public enum Verdict {
        PASS, BAD_CREDENTIALS, DISABLED, LOCKED
    }

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    private LoginPolicy() {
    }

    /**
     * 判定顺序即安全顺序：停用先于口令（否则禁用账号形同没禁用），锁定先于口令
     * （否则一次拒绝同时告诉对方"口令对但你来早了"，等于送一个口令校验 oracle）。
     */
    public static Verdict evaluate(AdminUserEntity user, boolean passwordMatches, LocalDateTime now) {
        if (user == null) {
            return Verdict.BAD_CREDENTIALS;
        }
        if (!STATUS_ACTIVE.equals(user.getStatus())) {
            return Verdict.DISABLED;
        }
        if (user.getLockUntil() != null && user.getLockUntil().isAfter(now)) {
            return Verdict.LOCKED;
        }
        return passwordMatches ? Verdict.PASS : Verdict.BAD_CREDENTIALS;
    }

    /** 失败一次之后账号该记成什么样：计数、是否锁定、锁到什么时候 */
    public record FailureState(int failCount, LocalDateTime lockUntil) {
    }

    /**
     * 口令错的记账。到达阈值时把计数清零而不是继续累加：
     * 不清零的话解锁后第一次失败就又顶到阈值，账号等于永久锁定。
     */
    public static FailureState onBadCredentials(AdminUserEntity user, int maxFailCount,
                                                int lockMinutes, LocalDateTime now) {
        int fails = user.getFailCount() + 1;
        if (fails < maxFailCount) {
            return new FailureState(fails, user.getLockUntil());
        }
        return new FailureState(0, now.plusMinutes(lockMinutes));
    }
}
