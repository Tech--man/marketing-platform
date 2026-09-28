package com.example.marketing.account.security;

import com.example.marketing.account.infrastructure.entity.ConsumerUserEntity;

import java.time.LocalDateTime;

/**
 * 消费者登录判定的唯一出处：把"能不能进"从 DB/Redis 里剥出来做成纯函数，
 * 边界情况全部由 ConsumerLoginPolicyTest 钉住。
 *
 * <p>与后台的 LoginPolicy 是两份而不是泛型化：那一份的签名钉在 AdminUserEntity 上、
 * 且有 LoginPolicyTest 逐条锁死，为了省十几行去改它，等于让 C 端的改动有机会动到
 * 后台的登录语义。判定顺序的理由两边完全同源，这里原样保留：</p>
 *
 * <p>停用先于口令（否则禁用账号形同没禁用），锁定先于口令
 * （否则一次拒绝同时告诉对方"口令对但你来早了"，等于送一个口令校验 oracle）。</p>
 */
public final class ConsumerLoginPolicy {

    public enum Verdict {
        PASS, BAD_CREDENTIALS, DISABLED, LOCKED
    }

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    private ConsumerLoginPolicy() {
    }

    public static Verdict evaluate(ConsumerUserEntity user, boolean passwordMatches, LocalDateTime now) {
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
    public static FailureState onBadCredentials(ConsumerUserEntity user, int maxFailCount,
                                                int lockMinutes, LocalDateTime now) {
        int fails = user.getFailCount() + 1;
        if (fails < maxFailCount) {
            return new FailureState(fails, user.getLockUntil());
        }
        return new FailureState(0, now.plusMinutes(lockMinutes));
    }
}
