package com.example.marketing.discount.service;

import com.example.marketing.common.config.ConfigValues;
import com.example.marketing.discount.config.DiscountConfigDefinitions;
import com.example.marketing.discount.config.DiscountProperties;
import com.example.marketing.discount.domain.CalcInput;
import com.example.marketing.discount.domain.CalcResult;
import com.example.marketing.discount.engine.PromoEngine;
import com.example.marketing.discount.engine.RuleSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 优惠计算应用服务：独立线程池执行 + 超时降级。
 *
 * <p>计算跑在专用池（与 Web 线程隔离，防止规则风暴拖垮整个服务）；
 * {@code orTimeout} 只约束纯计算，到期后立即返回原价兜底结果（degraded=true），
 * 结算页可提示"优惠计算繁忙，以结算价为准"。队列打满同样走降级。</p>
 */
@Slf4j
@Service
public class DiscountCalcService {

    private final PromoEngine promoEngine;
    private final RuleCacheManager ruleCacheManager;
    private final DiscountProperties properties;
    private final ConfigValues values;
    private final ExecutorService calcPool;
    private final Timer calcTimer;
    private final Counter degradeCounter;

    public DiscountCalcService(PromoEngine promoEngine, RuleCacheManager ruleCacheManager,
                               DiscountProperties properties, ConfigValues values,
                               MeterRegistry meterRegistry) {
        this.promoEngine = promoEngine;
        this.ruleCacheManager = ruleCacheManager;
        this.properties = properties;
        this.values = values;
        this.calcPool = new ThreadPoolExecutor(4, 8, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2000),
                r -> {
                    Thread t = new Thread(r, "discount-calc");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.calcTimer = meterRegistry.timer("mkt.discount.calc");
        this.degradeCounter = meterRegistry.counter("mkt.discount.degraded");
    }

    public CalcResult calculate(CalcInput input) {
        long start = System.nanoTime();
        // 快照读取放在超时预算之外：它可能回源 DB，一旦计入预算就会把冷路径误判成计算超时
        RuleSnapshot snapshot = ruleCacheManager.snapshot();
        try {
            CalcResult result = CompletableFuture
                    .supplyAsync(() -> promoEngine.calculate(input, snapshot), calcPool)
                    .orTimeout(values.longOr(DiscountConfigDefinitions.CALC_TIMEOUT_MS,
                            properties.getCalcTimeoutMs()), TimeUnit.MILLISECONDS)
                    .join();
            calcTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return result;
        } catch (Exception e) {
            // 超时 / 池满 / 计算异常统一降级：返回原价，绝不阻断结算
            degradeCounter.increment();
            log.warn("[discount] 计算降级返回原价, userId={}, cause={}", input.getUserId(), e.getMessage());
            return CalcResult.original(input.totalAmount());
        }
    }

    @PreDestroy
    public void shutdown() {
        calcPool.shutdownNow();
    }
}
