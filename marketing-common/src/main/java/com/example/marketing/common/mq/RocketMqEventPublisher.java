package com.example.marketing.common.mq;

import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

/**
 * RocketMQ 实现（Full 形态）：destination = topic:tag，KEYS 头 = bizKey 便于消息轨迹查询。
 */
public class RocketMqEventPublisher implements EventPublisher {

    private final RocketMQTemplate rocketMQTemplate;

    public RocketMqEventPublisher(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    public boolean publish(String topic, String tag, String bizKey, String payload) {
        Message<String> message = MessageBuilder.withPayload(payload)
                .setHeader("KEYS", bizKey).build();
        SendResult result = rocketMQTemplate.syncSend(topic + ":" + tag, message);
        return result != null && result.getSendStatus() == SendStatus.SEND_OK;
    }
}
