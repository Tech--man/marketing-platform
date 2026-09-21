package com.example.marketing;

import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Lite 部署形态聚合启动入口：单 JVM 装配活动/券/优惠/秒杀四个业务模块。
 *
 * <p>与 Full 形态（四个独立进程 + RocketMQ）的关系：</p>
 * <ul>
 *   <li>业务代码零改动，仅装配层聚合（本类位于 com.example.marketing 包根，
 *       组件扫描天然覆盖各模块 controller/service/mapper）；</li>
 *   <li>MQ 换成 Redis Stream：排除 RocketMQAutoConfiguration（无消费容器/生产者），
 *       common 自动装配按 marketing.mq.type=redis-stream 选择 Stream 实现；</li>
 *   <li>各模块独立启动类与重复定义的 MybatisPlusConfig 通过 excludeFilters 排除，
 *       由本模块统一提供一份。</li>
 * </ul>
 *
 * <p>适用场景：2C4G 级小内存单机演示/功能验证；8G+ 环境请用 Full 形态。</p>
 */
@SpringBootApplication(exclude = RocketMQAutoConfiguration.class)
@EnableScheduling
@ComponentScan(basePackages = "com.example.marketing",
        excludeFilters = {
                // 各模块独立启动类（避免二次 @ComponentScan 与多 @SpringBootConfiguration）
                @ComponentScan.Filter(type = FilterType.REGEX,
                        pattern = "com\\.example\\.marketing\\.\\w+\\.Marketing\\w+Application"),
                // 四模块同名的 MybatisPlusConfig（bean 冲突），standalone 自带一份
                @ComponentScan.Filter(type = FilterType.REGEX,
                        pattern = "com\\.example\\.marketing\\.\\w+\\.config\\.MybatisPlusConfig")
        })
public class MarketingStandaloneApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingStandaloneApplication.class, args);
    }
}
