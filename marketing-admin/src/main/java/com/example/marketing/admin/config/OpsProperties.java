package com.example.marketing.admin.config;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * ④ 运维只读面的参数。
 *
 * <p>{@code metricsMode} 与 {@code targets} 是"形态差异"而不是"可随便填的偏好"：
 * LITE/dev 必须是 {@code local}（业务模块就在同一个 JVM 里），FULL 分进程才是
 * {@code proxy}。所以这里在启动期就把取值钉死——拼错一个字母会静默退回 proxy，
 * 然后在 LITE 上产出一堆"抓不到 8081-8084"的假 error，而那正是本面要用来报警的信号。
 * （同一条纪律见 ⑤ 的 {@code DEPLOY_FORM} 拼错退回 GLOBAL 并告警。）</p>
 */
@Data
@ConfigurationProperties(prefix = "marketing.admin.ops")
public class OpsProperties implements InitializingBean {

    public static final String MODE_LOCAL = "local";
    public static final String MODE_PROXY = "proxy";
    private static final Set<String> MODES = Set.of(MODE_LOCAL, MODE_PROXY);

    /** local = 读本 JVM 的 MeterRegistry；proxy = 抓各进程的 /actuator/prometheus */
    private String metricsMode = MODE_PROXY;

    /** 缓存 vs 账 的抽样条数：全量扫会在弱主机上打一圈 MGET */
    private int consistencySampleSize = 20;

    /** target 名 → host:port。值必须在 OpsTargets 的正面清单内，否则启动失败 */
    private Map<String, String> targets = new LinkedHashMap<>();

    @Override
    public void afterPropertiesSet() {
        if (!MODES.contains(metricsMode)) {
            throw new IllegalStateException("marketing.admin.ops.metrics-mode=\"" + metricsMode
                    + "\" 非法，只能是 " + MODES + "（LITE/dev 用 local，FULL 分进程用 proxy）");
        }
        if (consistencySampleSize < 1) {
            throw new IllegalStateException("marketing.admin.ops.consistency-sample-size 必须 >= 1");
        }
    }
}
