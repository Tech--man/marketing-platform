package com.example.marketing.common.mq;

/**
 * Redis Stream 消息处理器（Lite 形态消费端 SPI）。
 *
 * <p>业务消费者类同时实现本接口与 RocketMQListener：Full 形态由 rocketmq starter
 * 的监听容器驱动，Lite 形态由 {@link StreamConsumerRegistrar} 轮询 XREADGROUP 驱动，
 * 两种形态复用同一段处理逻辑（消费幂等由业务层三层幂等保证）。</p>
 */
public interface StreamMessageHandler {

    /** 订阅的 topic（对应 MKT_STREAM_{topic}） */
    String topic();

    /** 消费组名（与 Full 形态的 GID 常量保持一致） */
    String group();

    /** 处理消息体；抛异常视为失败 */
    void handle(String payload);

    /**
     * 本 handler 关心的 tag（W3.10，2026-09-30 第二轮复审）：默认不校验。
     * Redis Stream 通道不做 tag 路由（一个 topic 的消息全投给注册的 handler），
     * FULL 形态 RocketMQ 按 selectorExpression 分发——将来在同一 topic 上加第二个
     * tag/消费者时，两形态行为会分叉。覆写本方法让消费容器能把不属于自己的 tag
     * 显式 WARN 出来，而不是静默错投。
     */
    default boolean acceptsTag(String tag) {
        return true;
    }
}
