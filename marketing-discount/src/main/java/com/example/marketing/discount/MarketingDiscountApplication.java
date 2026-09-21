package com.example.marketing.discount;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 优惠计算引擎服务。
 *
 * <p>核心链路：规则 DSL 解析 → 倒排索引+位图候选剪枝 → 互斥组组合选择 →
 * 优惠分摊（尾差归末项）→ Caffeine/Redis/DB 三级规则缓存 → 超时降级。</p>
 */
@SpringBootApplication
public class MarketingDiscountApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingDiscountApplication.class, args);
    }
}
