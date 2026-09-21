package com.example.marketing.activity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 灰度投放配置：activityNo → 灰度规则。
 *
 * <p>接入 Nacos 后（profile=nacos），配置推送会触发 @RefreshScope 重建，
 * 实现"灰度比例在线调整、秒级生效"。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "marketing")
public class GrayProperties {

    /** key = 活动编号 */
    private Map<String, GrayRule> gray = new HashMap<>();

    @Data
    public static class GrayRule {
        /** 放量百分比 0-100，按 userId 取模命中 */
        private int percent = 0;
        /** 白名单用户（内测/压测账号） */
        private List<Long> whitelist = List.of();
    }
}
