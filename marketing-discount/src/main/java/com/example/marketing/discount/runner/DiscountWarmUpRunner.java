package com.example.marketing.discount.runner;

import com.example.marketing.discount.service.RuleCacheManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动预热：把首次规则快照重建（含 DB 回源）从请求路径挪到启动阶段。
 *
 * <p>没有它时，进程启动后的第一个计算请求要在超时预算内完成 DB 查询与索引构建，
 * 必然走降级返回原价。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiscountWarmUpRunner implements ApplicationRunner {

    private final RuleCacheManager ruleCacheManager;

    @Override
    public void run(ApplicationArguments args) {
        try {
            ruleCacheManager.snapshot();
        } catch (Exception e) {
            // 预热失败不阻塞启动：首个请求仍会按三级缓存路径懒重建
            log.error("[discount] 规则快照预热失败，将依赖请求路径懒重建", e);
        }
    }
}
