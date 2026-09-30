package com.example.marketing.admin.observe;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 本地源：读自己 JVM 里的 {@link MeterRegistry}。LITE/dev 用它——四个业务模块与后台
 * 同在 standalone 这一个进程里，指标本来就在手边，不需要绕一圈 HTTP。
 *
 * <p>刻意<b>不</b>自己把 Micrometer 映射成 Prometheus 名字（counter 要不要加 {@code _total}、
 * timer 有哪几条派生线），而是让 Micrometer 的 Prometheus registry 自己渲染，再交给
 * 与跨进程抓取<b>同一个解析器</b>。映射手写一遍就有两份命名规则会漂，而漂的表现是"读不到"，
 * 恰好是这段最不能有的错。</p>
 */
@Slf4j
public class LocalMeterSource implements MetricSource {

    /** 本地模式唯一的 target 名 */
    public static final String SELF = "self";

    private final MeterRegistry registry;

    public LocalMeterSource(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String mode() {
        return "local";
    }

    @Override
    public PrometheusTextParser.ParseResult scrape(String target) {
        if (!serves(target)) {
            throw new ScrapeException("本地指标源只服务 \"" + SELF + "\"，不认 target=" + target
                    + "（本形态下这些模块都在同一个进程里，没有可抓的兄弟进程）");
        }
        if (!(registry instanceof PrometheusMeterRegistry prometheus)) {
            throw new ScrapeException("当前 MeterRegistry 不是 prometheus 实现（"
                    + registry.getClass().getName() + "），本地无法渲染线格式文本；"
                    + "宁缺勿假：不返回空结果冒充\"这个进程没有指标\"");
        }
        return PrometheusTextParser.parse(prometheus.scrape());
    }

    @Override
    public boolean serves(String target) {
        return SELF.equals(target);
    }
}
