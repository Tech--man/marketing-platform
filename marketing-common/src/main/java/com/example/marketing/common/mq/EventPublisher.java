package com.example.marketing.common.mq;

/**
 * 事件发布抽象：屏蔽具体 MQ 实现（RocketMQ / Redis Stream）。
 *
 * <p>部署形态切换的唯一发送入口：Full 形态装配 {@link RocketMqEventPublisher}，
 * Lite 形态（marketing.mq.type=redis-stream）装配 {@link RedisStreamEventPublisher}。
 * 本地消息表的"登记-发送-确认-补偿"语义与实现无关，两种形态行为一致。</p>
 */
public interface EventPublisher {

    /**
     * 同步发送一条消息。
     *
     * @return 发送是否成功（失败不抛异常时返回 false，交由补偿链路重发）
     */
    boolean publish(String topic, String tag, String bizKey, String payload);
}
