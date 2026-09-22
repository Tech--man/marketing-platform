package com.example.marketing.common.idempotent;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * bizKey 拼装：幂等表、流水表、本地消息表的唯一索引是**全局**的，
 * 不同场景复用同一段业务编号会互相吞写入（见设计文档地雷 B/D）。
 * 所有写入方都必须经这里生成，不要手拼字符串。
 */
public final class BizKey {

    /** 与 DDL 里 biz_key 列宽一致 */
    public static final int MAX_LENGTH = 128;

    private BizKey() {
    }

    /**
     * @param scene 场景命名空间，如 grant / seckill / budget
     * @param scope 业务域内的隔离维度，如活动号、模板号
     * @param raw   调用方自己的业务编号
     */
    public static String of(String scene, String scope, String raw) {
        requirePart(scene, "scene");
        requirePart(scope, "scope");
        requirePart(raw, "raw");
        return bounded(scene.trim() + ":" + scope.trim() + ":" + raw.trim());
    }

    /**
     * 两段形态：没有隔离维度时用这个（如 {@code grant:requestId}）。
     *
     * <p>字面值必须能被消费端从事件里原样还原 —— 消费端 confirm 与同步链路登记走的是同一个键，
     * 多一个维度就要多带一个字段，容易对不上。</p>
     */
    public static String of(String scene, String raw) {
        requirePart(scene, "scene");
        requirePart(raw, "raw");
        return bounded(scene.trim() + ":" + raw.trim());
    }

    private static String bounded(String joined) {
        if (joined.length() <= MAX_LENGTH) {
            return joined;
        }
        // 超长不能直接截断：截断会让两个长编号撞成同一个键，正是要防的静默丢写入。
        // 保留可读前缀 + 全串哈希后缀，长度有界且确定。
        int cut = joined.indexOf(':');
        String head = cut < 0 ? joined : joined.substring(0, cut + 1);
        int headKeep = Math.min(head.length(), MAX_LENGTH - HASH_LENGTH - 1);
        return joined.substring(0, headKeep) + "#" + sha256Hex(joined).substring(0, HASH_LENGTH);
    }

    /** 哈希后缀长度：够长到实际不会撞，又给前缀留出可读空间 */
    private static final int HASH_LENGTH = 32;

    private static void requirePart(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "bizKey 的 " + name + " 不能为空");
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
