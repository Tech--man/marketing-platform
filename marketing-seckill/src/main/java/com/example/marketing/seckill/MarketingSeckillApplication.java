package com.example.marketing.seckill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 秒杀中心：库存分桶预热 → Lua 原子抢购（定位分桶 + 借桶）→ 排队 token →
 * MQ 异步下单（unique(activity_no,user_id) 防重）→ 模拟支付回调 → 超时未支付回补。
 */
@SpringBootApplication
@EnableScheduling
public class MarketingSeckillApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingSeckillApplication.class, args);
    }
}
