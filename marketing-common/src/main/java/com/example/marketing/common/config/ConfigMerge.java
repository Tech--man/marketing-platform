package com.example.marketing.common.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按形态合并 {@code admin_config} 行：当前 form 覆盖 GLOBAL，其它 form 一律忽略。
 *
 * <p>"两档共用同一份 MySQL"下的安全阀，也是全项目唯一一处 form 优先级实现——
 * admin 发布快照、单测、以及 ④ 的"期望值 vs 生效值"对比都必须走它，
 * 出现第二处就有漂移。</p>
 */
public final class ConfigMerge {

    public record Row(String cfgKey, String form, String cfgValue) {
    }

    public static Map<String, String> merge(String ownForm, List<Row> rows) {
        String form = ConfigForm.resolve(ownForm);
        Map<String, String> global = new LinkedHashMap<>();
        Map<String, String> own = new LinkedHashMap<>();
        for (Row row : rows) {
            String normalized = ConfigForm.resolve(row.form());
            if (normalized.equals(form)) {
                own.put(row.cfgKey(), row.cfgValue());
            } else if (normalized.equals(ConfigKeys.GLOBAL)) {
                global.put(row.cfgKey(), row.cfgValue());
            }
        }
        Map<String, String> merged = new LinkedHashMap<>(global);
        merged.putAll(own);
        return merged;
    }

    private ConfigMerge() {
    }
}
