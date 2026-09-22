package com.example.marketing.common.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理台 JWT 编解码：网关侧必须能**无状态**验签（网关没有 DataSource，查不了库），
 * 所以签名、过期、时钟偏移容忍都由这里保证。
 */
class AdminTokenCodecTest {

    private static final String SECRET = "test-secret-please-ignore";
    private static final String OTHER_SECRET = "another-secret";

    private final AdminTokenCodec codec = new AdminTokenCodec(SECRET, Duration.ofSeconds(30));

    private AdminClaims claims(long now, long ttlSeconds, int pwdVersion) {
        return new AdminClaims(1L, "admin", "operator", pwdVersion, "jti-1",
                now, now + ttlSeconds);
    }

    @Test
    void issuesThreeSegmentTokenAndVerifiesBackToTheSameClaims() {
        long now = 1_800_000_000L;

        String token = codec.issue(claims(now, 900, 3));
        TokenVerifyResult result = codec.verify(token, now);

        assertThat(token.split("\\.")).hasSize(3);
        assertThat(result.status()).isSameAs(TokenVerifyResult.Status.OK);
        assertThat(result.claims().uid()).isEqualTo(1L);
        assertThat(result.claims().sub()).isEqualTo("admin");
        assertThat(result.claims().role()).isEqualTo("operator");
        assertThat(result.claims().jti()).isEqualTo("jti-1");
    }

    @Test
    void keepsThePasswordVersionClaimSoGatewayCanRejectTokensIssuedBeforeAChange() {
        long now = 1_800_000_000L;

        TokenVerifyResult result = codec.verify(codec.issue(claims(now, 900, 7)), now);

        assertThat(result.claims().pwdVersion()).isEqualTo(7);
    }

    @Test
    void rejectsTamperedPayloadAsSignatureFailure() {
        long now = 1_800_000_000L;
        String[] parts = codec.issue(claims(now, 900, 1)).split("\\.");
        // 真改 payload（base64url 解出来改角色再编回去），签名原样留着 —— 这才是伪造
        String json = new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                json.replace("\"operator\"", "\"admin\"").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(tampered).isNotEqualTo(String.join(".", parts));
        assertThat(codec.verify(tampered, now).status()).isSameAs(TokenVerifyResult.Status.BAD_SIGNATURE);
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        long now = 1_800_000_000L;
        String token = new AdminTokenCodec(OTHER_SECRET, Duration.ofSeconds(30)).issue(claims(now, 900, 1));

        assertThat(codec.verify(token, now).status()).isSameAs(TokenVerifyResult.Status.BAD_SIGNATURE);
    }

    @Test
    void rejectsExpiredToken() {
        long now = 1_800_000_000L;

        assertThat(codec.verify(codec.issue(claims(now - 1_000, 100, 1)), now).status())
                .isSameAs(TokenVerifyResult.Status.EXPIRED);
    }

    @Test
    void toleratesClockSkewWithinTheConfiguredWindow() {
        long now = 1_800_000_000L;
        // 签发方比校验方快 10 秒：exp 刚过，但落在 30s 容忍窗内
        String token = codec.issue(claims(now - 100, 90, 1));

        assertThat(codec.verify(token, now + 20).status()).isSameAs(TokenVerifyResult.Status.OK);
        assertThat(codec.verify(token, now + 60).status()).isSameAs(TokenVerifyResult.Status.EXPIRED);
    }

    @Test
    void rejectsGarbageTokensWithoutThrowing() {
        assertThat(codec.verify("not-a-jwt", 1_800_000_000L).status())
                .isSameAs(TokenVerifyResult.Status.MALFORMED);
        assertThat(codec.verify("", 1_800_000_000L).status())
                .isSameAs(TokenVerifyResult.Status.MALFORMED);
        assertThat(codec.verify(null, 1_800_000_000L).status())
                .isSameAs(TokenVerifyResult.Status.MALFORMED);
    }
}
