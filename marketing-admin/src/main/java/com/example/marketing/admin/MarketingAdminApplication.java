package com.example.marketing.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 管理后台独立启动入口（FULL 扩容档用）。LITE 服役档不启这个类 ——
 * 它由 standalone 聚进同一个 JVM，不为后台新增一个常驻进程。
 *
 * <p>没有 @EnableScheduling：后台没有任何周期任务，多一条调度线程就是白给的内存。</p>
 */
@SpringBootApplication
public class MarketingAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingAdminApplication.class, args);
    }
}
