package com.example.marketing.admin.config;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ④ 运维只读面的参数。
 *
 * <p>形态差异只有 <b>targets（抓谁）</b> 与 <b>localTargets（哪些进程就在我这个 JVM 里）</b>
 * 两件事，而且后者是从前者里挑名字——不需要一个"模式"开关。原先我按
 * {@code metrics-mode=local|proxy} 二选一实现过一版，那是错的：LITE 里四个业务模块
 * 与后台确实同进程，但<b>网关永远是独立进程</b>（母版事实 #2），二选一就让 LITE 的
 * 运维面永远看不到网关的限流拒绝数。现在两件事同时成立：{@code self} 走本地 registry，
 * {@code marketing-gateway} 走 HTTP。</p>
 */
@Data
@ConfigurationProperties(prefix = "marketing.admin.ops")
public class OpsProperties implements InitializingBean {

    /** target 名 → host:port。值必须在 OpsTargets 的正面清单内，否则启动失败 */
    private Map<String, String> targets = new LinkedHashMap<>();

    /**
     * 这些 target 的指标就在本进程的 MeterRegistry 里，不走 HTTP。
     * LITE/dev 填 {@code [self]}；FULL 分进程留空。
     */
    private List<String> localTargets = new ArrayList<>();

    /** 缓存 vs 账 的抽样条数（各模块自检内部另有上限，这里管的是视图层） */
    private int consistencySampleSize = 20;

    /**
     * 周期任务名，用来查各自的去重键。三份字面量长在三个模块的 job 里
     * （{@code coupon-expire} / {@code seckill-timeout} / {@code local-message-retry}），
     * ④ 跨不过模块边界，所以这里给一份可配置的对齐清单：漂了的表现是该任务恒 held=false，
     * 而不是报错——读数说明里也写了这一点。
     */
    private List<String> scheduledTasks = new ArrayList<>(List.of(
            "coupon-expire", "seckill-timeout", "local-message-retry"));

    @Override
    public void afterPropertiesSet() {
        if (consistencySampleSize < 1) {
            throw new IllegalStateException("marketing.admin.ops.consistency-sample-size 必须 >= 1");
        }
        for (String local : localTargets) {
            if (!targets.containsKey(local)) {
                throw new IllegalStateException("marketing.admin.ops.local-targets 里的 [" + local
                        + "] 不在 targets 清单内：本地源只能服务一个已配置的 target");
            }
        }
        if (targets.isEmpty()) {
            throw new IllegalStateException("marketing.admin.ops.targets 为空：整个只读面会静默变成"
                    + "「什么都读不到」。FULL 请下发 ops.targets.*，LITE 至少给 self 与网关");
        }
    }
}
