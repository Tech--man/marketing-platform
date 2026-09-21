package com.example.marketing.discount.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 优惠引擎可调参数（支持 Nacos 刷新）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "marketing.discount")
public class DiscountProperties {

    /** 单次计算超时（毫秒），超时降级返回原价。目标 P99 < 20ms，留足余量 */
    private long calcTimeoutMs = 50;

    /** 整单最多叠加规则数（最优组合选择的数量约束） */
    private int maxRulesPerOrder = 5;

    /** 规则快照 Redis 版本号 key */
    private String versionKey = "discount:rule:version";

    /** 本地快照最长可用时间（秒）：超过后强制比对 Redis 版本 */
    private long snapshotCheckSeconds = 5;
}
