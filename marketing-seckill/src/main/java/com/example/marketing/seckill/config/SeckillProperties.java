package com.example.marketing.seckill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 秒杀可调参数（支持 Nacos 刷新）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "marketing.seckill")
public class SeckillProperties {

    /** 库存分桶数：热点 key 打散倍数，越大单 key 压力越小、借桶概率越高 */
    private int buckets = 16;

    /** 抢购 token 结果保留时长（秒），前端轮询窗口 */
    private long tokenTtlSeconds = 600;

    /** 订单未支付超时（秒），超时回补库存 */
    private long payTimeoutSeconds = 300;

    /** 防重购标记 TTL（秒）：覆盖活动全程 + 回补窗口 */
    private long boughtMarkTtlSeconds = 86400;
}
