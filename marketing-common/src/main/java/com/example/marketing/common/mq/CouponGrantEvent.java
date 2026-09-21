package com.example.marketing.common.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 领券事件消息体：Redis 预扣成功后经 MQ 异步落库。
 *
 * <p>幂等键 = requestId，消费端以 user_coupon.request_id 唯一索引兜底去重。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponGrantEvent implements Serializable {

    /** 客户端请求唯一 ID（幂等键） */
    private String requestId;
    private Long userId;
    private Long templateId;
    private String activityNo;
    /** 领取数量，默认 1 */
    private Integer quantity;
}
