package com.example.marketing.admin.dto;

/** 签发结果。expiresInSeconds 让前端能自己提前刷新，不必等 401 才知道过期。 */
public record LoginView(
        String token,
        String tokenType,
        long expiresInSeconds,
        Long userId,
        String username,
        String displayName,
        String role) {
}
