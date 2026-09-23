package com.example.marketing.common.config;

import java.util.Objects;

/**
 * 一个可在线改的参数。边界含端点。
 *
 * <p>STRING 用 {@code max} 表达长度上限、{@code min} 恒为 1：空值既可能是"想清空"
 * 也可能是"手滑删除"，这种歧义不该出现在限流阈值这类参数里。</p>
 *
 * @param defaultValue 代码/yml 出厂值，只用于展示"改回去是什么"，运行时不取它
 */
public record ConfigDefinition(String key, ConfigType type, long min, long max,
                               String defaultValue, String description) {

    public ConfigDefinition {
        Objects.requireNonNull(key, "配置键不能为空");
        Objects.requireNonNull(type, "配置类型不能为空");
        if (min > max) {
            throw new IllegalArgumentException("配置 " + key + " 的边界反了: min=" + min + " max=" + max);
        }
    }

    /** min/max 用 long：边界值常量（如 200_000L）常常就是 long，调用点不该为此强转 */
    public static ConfigDefinition ofInt(String key, int def, long min, long max, String desc) {
        return new ConfigDefinition(key, ConfigType.INT, min, max, String.valueOf(def), desc);
    }

    public static ConfigDefinition ofLong(String key, long def, long min, long max, String desc) {
        return new ConfigDefinition(key, ConfigType.LONG, min, max, String.valueOf(def), desc);
    }

    public static ConfigDefinition ofText(String key, int maxLength, String def, String desc) {
        return new ConfigDefinition(key, ConfigType.STRING, 1, maxLength, def, desc);
    }

    /** 数值型返回解析后的值（越界或非数字则 null）；STRING 恒返回 null，走长度校验 */
    public Long coerce(String raw) {
        if (raw == null || type == ConfigType.STRING) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            long v = Long.parseLong(trimmed);
            return v < min || v > max ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean accepts(String raw) {
        if (raw == null) {
            return false;
        }
        if (type == ConfigType.STRING) {
            String trimmed = raw.trim();
            return !trimmed.isEmpty() && trimmed.length() <= max;
        }
        return coerce(raw) != null;
    }

    public int intDefault() {
        return Integer.parseInt(defaultValue);
    }

    public long longDefault() {
        return Long.parseLong(defaultValue);
    }
}
