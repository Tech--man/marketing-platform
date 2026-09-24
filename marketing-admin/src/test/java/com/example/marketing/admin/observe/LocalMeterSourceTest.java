package com.example.marketing.admin.observe;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地源不自己发明"Micrometer → Prometheus 名字"的映射：它直接把 registry 渲染出来的文本
 * 交给同一个解析器。理由很实际——一旦自己映射，就有两份命名规则会漂，而漂的表现是"读不到"，
 * 恰好是本段最不能有的那类错。
 *
 * <p>代价是必须显式处理"registry 不是 Prometheus 的那个实现"：此时抛错，不能返回空列表
 * 冒充"这个进程没有指标"。</p>
 */
class LocalMeterSourceTest {

    @Test
    @DisplayName("本地模式只服务 self，别的 target 一律不接")
    void modeAndServedTargets() {
        LocalMeterSource source = new LocalMeterSource(
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
        assertEquals("local", source.mode());
        assertTrue(source.serves(LocalMeterSource.SELF));
        assertTrue(!source.serves("marketing-activity"), "LITE 下去抓兄弟进程是错的：那里根本没有兄弟进程");
    }

    @Test
    @DisplayName("counter/gauge/timer 经同一条解析路径取到，_total 由渲染方负责")
    void readsMicrometerMetersThroughTheSameParser() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.counter("marketing.audit.drained").increment(4.0);
        registry.counter("mkt.job.dedup_skipped", "task", "seckill-timeout").increment(2.0);
        Timer timer = Timer.builder("mkt.discount.calc").register(registry);
        timer.record(Duration.ofMillis(3));

        LocalMeterSource source = new LocalMeterSource(registry);
        PrometheusTextParser.ParseResult result = source.scrape(LocalMeterSource.SELF);

        assertEquals(4.0, PrometheusTextParser.valueOf(
                PrometheusTextParser.byName(result.samples(), "marketing.audit.drained"), null, null));
        assertEquals(2.0, PrometheusTextParser.valueOf(
                PrometheusTextParser.byName(result.samples(), "mkt.job.dedup_skipped"),
                "task", "seckill-timeout"));
        List<MetricSample> timerLines = PrometheusTextParser.byName(result.samples(), "mkt.discount.calc.seconds");
        assertEquals(3, timerLines.size(), "timer 渲染成 _count/_sum/_max 三条线，基名一次取全");
        assertEquals(1.0, timerLines.stream()
                .filter(s -> s.name().equals("mkt_discount_calc_seconds_count"))
                .findFirst().orElseThrow().value());
        // 基名要带 _seconds：Micrometer 给 timer 的 Prometheus 名字加了单位后缀，
        // 按代码里的 meter 名（mkt.discount.calc）取会一条也取不到。这条断言是给"读不到≠0"
        // 这个纪律兜底的——写错名字必须表现为空列表，而不是 0。
        assertTrue(PrometheusTextParser.byName(result.samples(), "mkt.discount.calc").isEmpty());
    }

    @Test
    @DisplayName("micrometer 1.12 会在 tag 列表尾部多打一个逗号，解析必须容住")
    void trailingCommaInTags() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.counter("mkt.job.dedup_skipped", "task", "seckill-timeout").increment(2.0);
        PrometheusTextParser.ParseResult result =
                new LocalMeterSource(registry).scrape(LocalMeterSource.SELF);

        assertEquals(0, result.malformedLines(), "带 tag 的 counter 被当成坏行 = 所有维度读数静默消失");
        assertEquals(2.0, PrometheusTextParser.valueOf(
                PrometheusTextParser.byName(result.samples(), "mkt.job.dedup_skipped"),
                "task", "seckill-timeout"));
    }

    @Test
    @DisplayName("registry 不是 Prometheus 实现时报错而不是给空大盘")
    void nonPrometheusRegistryFailsLoudly() {
        LocalMeterSource source = new LocalMeterSource(new SimpleMeterRegistry());

        ScrapeException e = assertThrows(ScrapeException.class, () -> source.scrape(LocalMeterSource.SELF));
        assertTrue(e.getMessage().contains("prometheus"),
                "报错要说清缺的是哪个实现: " + e.getMessage());
    }

    @Test
    @DisplayName("抓未知 target 直接拒：不能悄悄返回一个空结果")
    void unknownTargetRejected() {
        LocalMeterSource source = new LocalMeterSource(
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));

        assertThrows(ScrapeException.class, () -> source.scrape("marketing-coupon"));
    }
}
