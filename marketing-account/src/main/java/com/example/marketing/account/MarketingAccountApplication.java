package com.example.marketing.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 消费者账号服务独立启动入口（FULL 扩容档用）。LITE 服役档不启这个类 ——
 * 它由 standalone 聚进同一个 JVM，和后台一样不为身份件新增一个常驻进程。
 *
 * <p>没有 @EnableScheduling：正确性靠"用到了才判"（refresh 校验时查行），
 * 不依赖定时任务；行保留期的低频清理由 {@code ConsumerSessionCleanup} 自起线程
 * 承担（12h 一轮、RedisLeaseLock 防多副本重复、走 idx_refresh_expire——
 * 不是那个"每分钟扫全表还要配锁"的方案，2026-09-29 第四批起）。</p>
 */
@SpringBootApplication
public class MarketingAccountApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingAccountApplication.class, args);
    }
}
