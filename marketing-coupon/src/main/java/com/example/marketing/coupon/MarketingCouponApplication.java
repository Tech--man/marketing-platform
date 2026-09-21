package com.example.marketing.coupon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 券中心：模板管理、领券（Redis Lua 预扣 + MQ 削峰落库 + 幂等）、核销（分布式锁 + 状态机）、过期与对账补偿。
 *
 * <p>领券链路：网关限流 → 风控 → 模板校验 → Lua 原子预扣 → 本地消息表 + MQ → 消费落库 → 结果查询 → 对账补偿。</p>
 */
@SpringBootApplication
@EnableScheduling
public class MarketingCouponApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingCouponApplication.class, args);
    }
}
