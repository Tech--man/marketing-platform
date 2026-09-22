package com.example.marketing.admin.audit;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 审计用的请求摘要：把敏感值换掉、把超长剪掉，然后才允许进库。
 *
 * <p>刻意<b>不</b>先 JSON 解析再序列化：审计要记的是"客户端到底发了什么"，
 * 解析失败的正好是最需要留档的那批请求（畸形 body、探针、攻击尝试）。
 * 所以这里是纯正则就地替换，任何输入都能产出一个摘要，不会因为
 * "不是合法 JSON" 就把整条审计丢掉。</p>
 */
public final class RequestSummary {

    /** 落库列宽 512，超出部分剪掉并留记号 */
    static final int MAX_LENGTH = 512;
    private static final String MASK = "***";
    private static final String TRUNCATED = "...(truncated)";
    private static final Set<String> NEEDLES = Set.of(
            "password", "passwd", "pwd", "secret", "token", "credential", "authorization", "accesstoken");

    /** "key": "value"（含转义）与 "key": 123 两种 */
    private static final Pattern JSON_STRING = Pattern.compile("\"([^\"]*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern JSON_OTHER = Pattern.compile("\"([^\"]*)\"\\s*:\\s*(-?[0-9.]+|[A-Za-z]+)");
    /** 查询串 / 松散文本里的 key=value */
    private static final Pattern KEY_VALUE = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)=([^&\\s,]*)");

    private RequestSummary() {
    }

    public static String of(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String masked = maskJson(raw);
        masked = maskKeyValue(masked);
        return masked.length() <= MAX_LENGTH ? masked : masked.substring(0, MAX_LENGTH) + TRUNCATED;
    }

    private static String maskJson(String raw) {
        Matcher strings = JSON_STRING.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (strings.find()) {
            String replacement = isSensitive(strings.group(1))
                    ? "\"" + strings.group(1) + "\": \"" + MASK + "\""
                    : strings.group(0);
            strings.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        strings.appendTail(out);

        Matcher others = JSON_OTHER.matcher(out);
        StringBuilder finalOut = new StringBuilder();
        while (others.find()) {
            String replacement = isSensitive(others.group(1))
                    ? "\"" + others.group(1) + "\": " + MASK
                    : others.group(0);
            others.appendReplacement(finalOut, Matcher.quoteReplacement(replacement));
        }
        others.appendTail(finalOut);
        return finalOut.toString();
    }

    private static String maskKeyValue(String raw) {
        Matcher matcher = KEY_VALUE.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = isSensitive(matcher.group(1))
                    ? matcher.group(1) + "=" + MASK
                    : matcher.group(0);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 键名去掉下划线/大小写后含任一敏感词根即算敏感：oldPassword、new_password、client_secret 都要盖住 */
    static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.replace("_", "").replace("-", "").toLowerCase();
        return NEEDLES.stream().anyMatch(normalized::contains);
    }
}
