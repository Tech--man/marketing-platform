package com.example.marketing.common.mq;

/**
 * RocketMQ Topic / Tag / 消费组 常量定义。
 *
 * <p>Topic 规划：按业务域划分，Tag 区分事件类型；生产环境可通过 ONS/自建集群隔离。</p>
 */
public final class MqTopics {

    /** 领券异步落库 */
    public static final String TOPIC_COUPON_GRANT = "MKT_COUPON_GRANT";
    /** 秒杀异步下单 */
    public static final String TOPIC_SECKILL_ORDER = "MKT_SECKILL_ORDER";

    public static final String TAG_GRANT = "GRANT";
    public static final String TAG_ORDER = "ORDER";

    /** 消费组（一个服务一个组，避免订阅关系不一致） */
    public static final String GROUP_COUPON = "GID_MARKETING_COUPON";
    public static final String GROUP_SECKILL = "GID_MARKETING_SECKILL";

    private MqTopics() {
    }
}
