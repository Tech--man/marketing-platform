package com.example.marketing.common.security;

/**
 * 管理台 token 的声明集，字段顺序即构造顺序：
 * {@code new AdminClaims(uid, username, role, pwdVersion, jti, issuedAt, expiresAt)}。
 *
 * @param uid        账号 ID
 * @param sub        用户名
 * @param role       角色（admin / operator / read-only，网关只做粗筛，细粒度在 admin 侧判）
 * @param pwdVersion 口令版本，与库里的 pwd_version 比对；改密即 +1，旧 token 全部作废
 * @param jti        会话 ID，登出/强制下线按它拉黑
 * @param iat        签发时刻（epoch 秒）
 * @param exp        过期时刻（epoch 秒）
 */
public record AdminClaims(long uid, String sub, String role, int pwdVersion, String jti, long iat, long exp) {
}
