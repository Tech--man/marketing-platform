package com.example.marketing.seckill.runner;

import com.example.marketing.seckill.service.SeckillWarmUpService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动预热入口：口径与运维重预热共用 {@link SeckillWarmUpService}，这里只负责"什么时候跑"。
 *
 * <p>Redis 不可用时仅告警，抢购请求会以"未预热"错误快速失败而不是拖挂服务。</p>
 */
@Component
@RequiredArgsConstructor
public class SeckillWarmUpRunner implements ApplicationRunner {

    private final SeckillWarmUpService warmUpService;

    @Override
    public void run(ApplicationArguments args) {
        warmUpService.warmAllOnline();
    }
}
