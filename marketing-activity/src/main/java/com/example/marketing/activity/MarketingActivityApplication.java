package com.example.marketing.activity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 活动中心：活动全生命周期（状态机）、预算控制（Redis 预扣 + DB 流水）、灰度投放（Nacos 配置推送）。
 */
@SpringBootApplication
@EnableScheduling
public class MarketingActivityApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingActivityApplication.class, args);
    }
}
