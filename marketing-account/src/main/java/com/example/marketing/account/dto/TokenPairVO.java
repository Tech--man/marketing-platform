package com.example.marketing.account.dto;

/**
 * 一对凭证。{@code accessToken} 短寿命、参与每次请求验签；
 * {@code refreshToken} 长寿命、只能打到 /api/auth/refresh，绝不能拿去访问业务接口。
 */
public record TokenPairVO(
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        long uid,
        String identifier,
        String nickname) {
}
