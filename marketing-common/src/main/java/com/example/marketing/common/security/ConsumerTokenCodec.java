package com.example.marketing.common.security;

import com.example.marketing.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消费者 JWT（HS256）编解码。与 {@link AdminTokenCodec} 同构，但<b>密钥独立</b>。
 *
 * <p>为什么不复用 {@code ADMIN_JWT_SECRET}：一把密钥两种用途，等于后台签出的 token
 * 可以被消费者侧接受（反之亦然）—— 冒烟里"两套凭证不互通是双向的"那条断言
 * （smoke-test.sh:517-520）就会变成只靠 claim 形状侥幸成立。独立密钥让"不互通"
 * 落在 HMAC 这一层，与 claim 内容无关。</p>
 *
 * <p>放在 common 的理由与后台件相同：签发在 account 服务（要查库验口令），校验在网关
 * （WebFlux，无 DataSource，只能无状态验签），业务服务还要第三处自验，三侧必须同一实现。</p>
 */
public class ConsumerTokenCodec {

    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long skewSeconds;

    public ConsumerTokenCodec(String secret, Duration skew) {
        this.secret = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);
        this.skewSeconds = skew == null ? 0L : skew.toSeconds();
    }

    public String issue(ConsumerClaims claims) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uid", claims.uid());
        payload.put("sub", claims.sub());
        payload.put("jti", claims.jti());
        payload.put("typ", claims.type());
        payload.put("iat", claims.iat());
        payload.put("exp", claims.exp());
        String signingInput = ENCODER.encodeToString(HEADER.getBytes(StandardCharsets.UTF_8))
                + "." + ENCODER.encodeToString(JsonUtils.toJson(payload).getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + ENCODER.encodeToString(sign(signingInput));
    }

    /**
     * 校验并要求 token 类型等于 {@code expectedType}。
     *
     * @param nowEpochSec 校验方当前时刻（epoch 秒）。显式传入而不是读系统时钟，
     *                    既方便测时钟偏移，也让"哪台机器的钟"这个问题在调用点可见。
     */
    public ConsumerVerifyResult verify(String token, long nowEpochSec, String expectedType) {
        if (token == null || token.isBlank()) {
            return ConsumerVerifyResult.fail(ConsumerVerifyResult.Status.MALFORMED);
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return ConsumerVerifyResult.fail(ConsumerVerifyResult.Status.MALFORMED);
        }
        byte[] expected = sign(parts[0] + "." + parts[1]);
        byte[] given;
        ConsumerClaims claims;
        try {
            given = DECODER.decode(parts[2]);
            Map<String, Object> payload = JsonUtils.parse(
                    new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Object>>() {
                    });
            claims = toClaims(payload);
        } catch (Exception e) {
            return ConsumerVerifyResult.fail(ConsumerVerifyResult.Status.MALFORMED);
        }
        // 定长比较，避免按字节早退造成的时序侧信道
        if (!MessageDigest.isEqual(expected, given)) {
            return ConsumerVerifyResult.fail(ConsumerVerifyResult.Status.BAD_SIGNATURE);
        }
        if (nowEpochSec > claims.exp() + skewSeconds) {
            return ConsumerVerifyResult.fail(ConsumerVerifyResult.Status.EXPIRED);
        }
        // 类型判定放在签名与有效期之后：一枚伪造的 refresh 该报"验签失败"，
        // 而不是借这个分支多告诉攻击者"这枚 token 的 typ 字段是有效的"。
        if (expectedType != null && !expectedType.equals(claims.type())) {
            return ConsumerVerifyResult.fail(ConsumerVerifyResult.Status.WRONG_TYPE);
        }
        return ConsumerVerifyResult.ok(claims);
    }

    public ConsumerVerifyResult verifyAccess(String token, long nowEpochSec) {
        return verify(token, nowEpochSec, ConsumerClaims.TYPE_ACCESS);
    }

    public ConsumerVerifyResult verifyRefresh(String token, long nowEpochSec) {
        return verify(token, nowEpochSec, ConsumerClaims.TYPE_REFRESH);
    }

    private ConsumerClaims toClaims(Map<String, Object> payload) {
        Object type = payload.get("typ");
        if (!(type instanceof String s)) {
            throw new IllegalArgumentException("claims 缺少 token 类型 typ");
        }
        return new ConsumerClaims(
                number(payload.get("uid")),
                text(payload.get("sub")),
                text(payload.get("jti")),
                s,
                number(payload.get("iat")),
                number(payload.get("exp")));
    }

    private static long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalArgumentException("claims 字段缺失或非数值: " + value);
    }

    private static String text(Object value) {
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("claims 字段缺失或非字符串: " + value);
    }

    private byte[] sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC 初始化失败", e);
        }
    }
}
