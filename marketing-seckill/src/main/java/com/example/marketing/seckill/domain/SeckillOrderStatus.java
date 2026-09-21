package com.example.marketing.seckill.domain;

/**
 * 秒杀订单状态机：CREATED →(支付回调) PAID；CREATED →(超时/取消) CANCELLED。
 */
public enum SeckillOrderStatus {

    /** 已创建待支付 */
    CREATED,

    /** 已支付（终态） */
    PAID,

    /** 已取消/超时回补（终态） */
    CANCELLED
}
