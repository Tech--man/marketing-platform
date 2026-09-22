package com.example.marketing.admin.security;

/**
 * 后台调用人。
 *
 * <p>字段全部来自网关注入的 {@code X-Admin-*} 头，因此不含 pwdVersion / iat / exp：
 * 那三个是"验签时才需要的事实"，验签已经在网关做完了。这里刻意不复用 AdminClaims，
 * 是为了让"这个对象里没有未经验证的东西"这件事在类型上可见 ——
 * 塞进默认值 0 的字段迟早会被某处当成真值用。</p>
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
