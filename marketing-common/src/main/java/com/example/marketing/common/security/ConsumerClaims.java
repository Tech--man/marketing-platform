package com.example.marketing.common.security;

/**
 * 消费者 token 的声明集。字段顺序即构造顺序：
 * {@code new ConsumerClaims(uid, username, jti, type, issuedAt, expiresAt)}。
 *
 * <p><b>刻意没有 {@code ver}（口令版本）这一位</b>：后台的 token 里带了它，但校验时
 * 从不与库里的 {@code pwd_version} 比对（见 AdminClaims 的注与 ①② spec §9）——
 * 一枚"看着像安全边界其实没接线"的 claim 比没有更糟。改密/停用杀光全部会话这件事，
 * 这里由 {@code consumer:bump:{uid}} 那把键承担（{@code iat} 早于作废时刻即拒），
 * 不需要每次请求多查一次库，所以干脆不写这个字段。</p>
 *
 * @param uid    消费者账号 ID（consumer_user.id）
 * @param sub    登录名
 * @param jti    会话 ID，登出/强制下线按它拉黑
 * @param type   {@link #TYPE_ACCESS} 或 {@link #TYPE_REFRESH}。refresh 只能换 access，
 *               不能当访问凭证用——少了这一位，一枚泄露的 refresh token 就能直接下单
 * @param iat    签发时刻（epoch 秒），整号作废判定要用它
 * @param exp    过期时刻（epoch 秒）
 */
public record ConsumerClaims(long uid, String sub, String jti, String type, long iat, long exp) {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    public boolean isAccess() {
        return TYPE_ACCESS.equals(type);
    }

    public boolean isRefresh() {
        return TYPE_REFRESH.equals(type);
    }
}
