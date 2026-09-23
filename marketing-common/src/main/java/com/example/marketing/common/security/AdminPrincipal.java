package com.example.marketing.common.security;

/**
 * 后台调用人。
 *
 * <p>两个来源，都不含未经验证的东西：admin 侧从网关注入的 {@code X-Admin-*} 头构造
 * （网关验过签名、有效期与吊销才发这些头）；业务侧从 {@code X-Admin-Token} 的 claims 构造
 * （③ 起业务端口不读裸头，见 {@link AdminRequestIdentity}）。因此不含 pwdVersion / iat / exp：
 * 那三个是"验签时才需要的事实"。这里刻意不复用 AdminClaims，是为了让
 * "这个对象里没有未经验证的东西"这件事在类型上可见 —— 塞进默认值 0 的字段迟早会被某处当成真值用。</p>
 *
 * @param uid      账号 ID
 * @param username 登录名
 * @param role     admin / operator / read-only
 * @param jti      会话 ID（登出、改密要按它作废）
 */
public record AdminPrincipal(long uid, String username, String role, String jti) {

    public boolean hasRole(String... allowed) {
        for (String role : allowed) {
            if (this.role.equals(role)) {
                return true;
            }
        }
        return false;
    }
}
