package com.example.marketing.coupon.runner;

import com.example.marketing.coupon.service.CouponTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动预热：服务重启后把所有 ACTIVE 模板库存补进 Redis（SETNX 语义，多实例安全）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponWarmUpRunner implements ApplicationRunner {

    private final CouponTemplateService templateService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            templateService.warmAllActive();
        } catch (Exception e) {
            // 预热失败不阻塞启动：领券链路由 NOT_WARMED 分支按模板懒加载兜底
            log.error("[coupon] 启动预热失败，将依赖领券时懒加载预热", e);
        }
    }
}
