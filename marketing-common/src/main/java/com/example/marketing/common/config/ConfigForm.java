package com.example.marketing.common.config;

/**
 * {@code DEPLOY_FORM} 的归一与白名单。
 *
 * <p>认不出的取值一律退回 GLOBAL 并由调用方告警：宁可少看到一层覆盖，
 * 也不能把某个形态的阈值套到形态名拼错的进程上。</p>
 */
public final class ConfigForm {

    public static String resolve(String raw) {
        if (raw == null) {
            return ConfigKeys.GLOBAL;
        }
        String normalized = raw.trim().toUpperCase();
        return ConfigKeys.FORMS.contains(normalized) ? normalized : ConfigKeys.GLOBAL;
    }

    /** 归一后仍是 GLOBAL 而原值非空 → 形态名写错了，要喊出来 */
    public static boolean unrecognized(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return false;
        }
        return !ConfigKeys.FORMS.contains(raw.trim().toUpperCase());
    }

    private ConfigForm() {
    }
}
