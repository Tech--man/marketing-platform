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
 * 管理台 JWT（HS256）编解码。
 *
 * <p>放在 common 是因为签发在 admin 模块（要查库验口令）、校验在网关（WebFlux，没有 DataSource，
 * 只能无状态验签），两侧必须共用同一个实现。用 JDK 的 {@code Mac} + 已有 Jackson 手写，
 * 不引 JJWT（也就是一层 base64url + HMAC，没有更多魔法）。</p>
 */
public class AdminTokenCodec {

    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long skewSeconds;

    public AdminTokenCodec(String secret, Duration skew) {
        this.secret = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);
        this.skewSeconds = skew == null ? 0L : skew.toSeconds();
    }

    public String issue(AdminClaims claims) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uid", claims.uid());
        payload.put("sub", claims.sub());
        payload.put("rol", claims.role());
        payload.put("ver", claims.pwdVersion());
        payload.put("jti", claims.jti());
        payload.put("iat", claims.iat());
        payload.put("exp", claims.exp());
        String signingInput = ENCODER.encodeToString(HEADER.getBytes(StandardCharsets.UTF_8))
                + "." + ENCODER.encodeToString(JsonUtils.toJson(payload).getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + ENCODER.encodeToString(sign(signingInput));
    }

    /**
     * @param token       待校验 token
     * @param nowEpochSec 校验方当前时刻（epoch 秒）。显式传入而不是读系统时钟，
     *                    既方便测时钟偏移，也让"哪台机器的钟"这个问题在调用点可见。
     */
    public TokenVerifyResult verify(String token, long nowEpochSec) {
        if (token == null || token.isBlank()) {
            return TokenVerifyResult.fail(TokenVerifyResult.Status.MALFORMED);
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return TokenVerifyResult.fail(TokenVerifyResult.Status.MALFORMED);
        }
        byte[] expected = sign(parts[0] + "." + parts[1]);
        byte[] given;
        AdminClaims claims;
        try {
            given = DECODER.decode(parts[2]);
            Map<String, Object> payload = JsonUtils.parse(
                    new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Object>>() {
                    });
            claims = toClaims(payload);
        } catch (Exception e) {
            return TokenVerifyResult.fail(TokenVerifyResult.Status.MALFORMED);
        }
        // 定长比较，避免按字节早退造成的时序侧信道
        if (!MessageDigest.isEqual(expected, given)) {
            return TokenVerifyResult.fail(TokenVerifyResult.Status.BAD_SIGNATURE);
        }
        if (nowEpochSec > claims.exp() + skewSeconds) {
            return TokenVerifyResult.fail(TokenVerifyResult.Status.EXPIRED);
        }
        return TokenVerifyResult.ok(claims);
    }

    private AdminClaims toClaims(Map<String, Object> payload) {
        return new AdminClaims(
                number(payload.get("uid")),
                text(payload.get("sub")),
                text(payload.get("rol")),
                (int) number(payload.get("ver")),
                text(payload.get("jti")),
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
