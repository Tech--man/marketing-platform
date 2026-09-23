package com.example.marketing.admin.dto;

import java.util.List;

/**
 * 一个可改参数的完整视图。
 *
 * @param source FORM（本进程形态有自己的覆盖行）/ GLOBAL / DEFAULT（没人覆盖，跑出厂值）
 */
public record ConfigEntryView(String key, String service, String type, long min, long max,
                              String defaultValue, String description, String effectiveValue,
                              String source, List<ConfigFormValueView> rows) {
}
