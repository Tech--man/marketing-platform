package com.example.marketing.common.security;

/**
 * 已验签的消费者身份。与 {@link AdminPrincipal} 同样只装"验过的字段"，
 * 不含 iat/exp/type —— 让"这个对象里没有未经验证的东西"在类型上可见。
 *
 * <p>没有 role 字段是刻意的：消费者只有"是不是本人"这一种判定，
 * 后台那套 admin/operator/read-only 三层不适用于 C 端。</p>
 */
public record ConsumerPrincipal(long uid, String username, String jti) {
}
