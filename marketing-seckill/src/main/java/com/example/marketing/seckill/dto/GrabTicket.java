package com.example.marketing.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 抢购受理凭证：抢到库存名额即返回 ACCEPTED，订单结果凭 token 轮询。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GrabTicket {

    private String token;
    /** ACCEPTED = 已占库存名额，异步下单中 */
    private String status;
}
