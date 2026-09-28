package com.example.marketing.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 消费者 token 编解码：签发/验签/过期/类型/跨密钥。 */
class ConsumerTokenCodecTest {

    private static final String SECRET = "test-consumer-secret-0123456789";
    private static final Duration SKEW = Duration.ofSeconds(30);

    private final ConsumerTokenCodec codec = new ConsumerTokenCodec(SECRET, SKEW);

    private ConsumerClaims claims(String type, long iat, long exp) {
        return new ConsumerClaims(70001, "demo", "jti-abc", type, iat, exp);
    }

    @Test
    @DisplayName("签出的 access token 能被同一个 codec 验回，claims 逐项相等")
    void roundTrip() {
        long now = 1_800_000_000L;
        String token = codec.issue(claims(ConsumerClaims.TYPE_ACCESS, now, now + 900));
        ConsumerVerifyResult r = codec.verifyAccess(token, now);
        assertEquals(ConsumerVerifyResult.Status.OK, r.status());
        assertEquals(70001L, r.claims().uid());
        assertEquals("demo", r.claims().sub());
        assertEquals("jti-abc", r.claims().jti());
        assertTrue(r.claims().isAccess());
    }

    @Test
    @DisplayName("改一个字符就验签失败：签名不是装饰")
    void tamperedPayloadRejected() {
        long now = 1_800_000_000L;
        String token = codec.issue(claims(ConsumerClaims.TYPE_ACCESS, now, now + 900));
        String[] p = token.split("\\.");
        // 把 uid 换大的那枚 payload 替成另一个账号的
        String forged = codec.issue(new ConsumerClaims(70002, "demo", "jti-abc",
                ConsumerClaims.TYPE_ACCESS, now, now + 900));
        String mixed = p[0] + "." + forged.split("\\.")[1] + "." + p[2];
        assertEquals(ConsumerVerifyResult.Status.BAD_SIGNATURE, codec.verifyAccess(mixed, now).status());
    }

    @Test
    @DisplayName("过期判定含时钟偏移：exp 之内可容忍 skew，超出即拒")
    void expiryWithSkew() {
        long now = 1_800_000_000L;
        String token = codec.issue(claims(ConsumerClaims.TYPE_ACCESS, now, now + 900));
        assertEquals(ConsumerVerifyResult.Status.OK,
                codec.verifyAccess(token, now + 900 + 29).status(), "skew 内应放行");
        assertEquals(ConsumerVerifyResult.Status.EXPIRED,
                codec.verifyAccess(token, now + 900 + 31).status(), "超出 skew 应过期");
    }

    @Test
    @DisplayName("refresh 不能当 access 用，access 也不能当 refresh 用")
    void typeIsEnforced() {
        long now = 1_800_000_000L;
        String refresh = codec.issue(claims(ConsumerClaims.TYPE_REFRESH, now, now + 900));
        String access = codec.issue(claims(ConsumerClaims.TYPE_ACCESS, now, now + 900));
        assertEquals(ConsumerVerifyResult.Status.WRONG_TYPE, codec.verifyAccess(refresh, now).status());
        assertEquals(ConsumerVerifyResult.Status.WRONG_TYPE, codec.verifyRefresh(access, now).status());
        assertEquals(ConsumerVerifyResult.Status.OK, codec.verifyRefresh(refresh, now).status());
    }

    @Test
    @DisplayName("另一把密钥签的 token 一律无效（后台/消费者凭证不互通的落点）")
    void crossSecretRejected() {
        long now = 1_800_000_000L;
        ConsumerTokenCodec other = new ConsumerTokenCodec("a-completely-different-secret-987654", SKEW);
        String token = other.issue(claims(ConsumerClaims.TYPE_ACCESS, now, now + 900));
        assertEquals(ConsumerVerifyResult.Status.BAD_SIGNATURE, codec.verifyAccess(token, now).status());
    }

    @Test
    @DisplayName("垃圾输入不抛异常，一律 MALFORMED；空/缺段/null 都一样")
    void malformedNeverThrows() {
        long now = 1_800_000_000L;
        for (String junk : new String[]{"", "   ", "abc", "a.b", "a.b.c.d", "!!!.###.$$$"}) {
            ConsumerVerifyResult r = codec.verifyAccess(junk, now);
            assertEquals(ConsumerVerifyResult.Status.MALFORMED, r.status(), "输入: " + junk);
            assertNull(r.claims());
        }
    }

    @Test
    @DisplayName("同一条 claims 两次签发结果一致；不同 jti 结果不同")
    void deterministicPerClaims() {
        long now = 1_800_000_000L;
        assertEquals(codec.issue(claims(ConsumerClaims.TYPE_ACCESS, now, now + 900)),
                codec.issue(claims(ConsumerClaims.TYPE_ACCESS, now, now + 900)));
        assertNotEquals(codec.issue(claims(ConsumerClaims.TYPE_ACCESS, now, now + 900)),
                codec.issue(new ConsumerClaims(70001, "demo", "jti-OTHER",
                        ConsumerClaims.TYPE_ACCESS, now, now + 900)));
    }
}
