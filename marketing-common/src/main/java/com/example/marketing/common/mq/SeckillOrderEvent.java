package com.example.marketing.common.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀异步下单事件消息体：Redis 扣减成功后经 MQ 异步创建订单。
 *
 * <p>幂等键 = token（一次抢购请求一个 token），消费端以 seckill_order 的
 * unique(activity_no, user_id) 约束兜底防重复购买。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderEvent implements Serializable {

    /** 抢购令牌（幂等键，同时作为前端排队结果查询凭证） */
    private String token;
    private String activityNo;
    private Long userId;
    private Long itemId;
    /** 命中的库存分桶（失败回补时需要） */
    private Integer bucket;
}
