package com.example.marketing.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 消费者账号服务独立启动入口（FULL 扩容档用）。LITE 服役档不启这个类 ——
 * 它由 standalone 聚进同一个 JVM，和后台一样不为身份件新增一个常驻进程。
 *
 * <p>没有 @EnableScheduling：refresh token 的过期清理靠"用到了才判"与 DB 上的
 * expire_at 索引，不需要一个每分钟扫全表的定时任务（那在多副本下还得再配一把锁）。</p>
 */
@SpringBootApplication
public class MarketingAccountApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingAccountApplication.class, args);
    }
}
