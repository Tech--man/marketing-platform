package com.example.marketing.admin.observe;

import java.util.Map;

/**
 * 一条 Prometheus 样本。
 *
 * <p>{@code value} 保留原始 double 而不做取整：④ 的用法里有"两次取样的差"这种算法，
 * 取整会把小计数的增长吃掉。</p>
 */
public record MetricSample(String name, Map<String, String> tags, double value) {

    public String tag(String key) {
        return tags.get(key);
    }
}
