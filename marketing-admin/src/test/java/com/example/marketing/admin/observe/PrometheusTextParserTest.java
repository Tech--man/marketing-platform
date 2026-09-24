package com.example.marketing.admin.observe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prometheus 文本解析：④ 的所有读数最后都过这一道，解析歪了会<b>静默少报</b>。
 *
 * <p>三条最容易踩的线，每条都单独钉：</p>
 * <ol>
 *   <li>Micrometer 在 Prometheus 上给 counter 加 {@code _total} —— 按基名取时必须认这个后缀，
 *       否则每个计数器都读成"没有"；</li>
 *   <li>histogram/timer 是<b>多条派生线</b>（{@code _count}/{@code _sum}/{@code _max}/{@code bucket}），
 *       后缀要原样保留，调用方要的就是这几条不同的线；</li>
 *   <li>{@code #} 注释行长得和样本一样（{@code # HELP name ...}），不能当数据。</li>
 * </ol>
 *
 * <p>"名字对不上"必须是空列表而不是 0：④ 的整个价值前提是把"读不到"和"读到 0"分开
 * （母版 §7、段内 spec §4.1）。</p>
 */
class PrometheusTextParserTest {

    /** 真样本形状：counter 的 _total、带 tag、timer 派生线、注释、空行、两行脏数据。 */
    private static final String SAMPLE = """
            # HELP marketing_audit_drained Total number of audit rows drained
            # TYPE marketing_audit_drained counter
            marketing_audit_drained_total 1.0
            mkt_job_dedup_skipped_total{task="local-message-retry"} 3.0
            mkt_job_dedup_skipped_total{task="seckill-timeout"} 1.0
            http_server_requests_seconds_count{method="POST",uri="/api/coupon/grant",status="200",outcome="SUCCESS"} 12.0
            mkt_discount_calc_seconds_count 9.0
            mkt_discount_calc_seconds_sum 0.0042
            mkt_discount_calc_seconds_max 0.0011
            marketing_config_snapshot_version{application="marketing-standalone"} 7.0
            this_is_not_a_metric
            broken{label="unclosed
            """;

    private final PrometheusTextParser.ParseResult parsed = PrometheusTextParser.parse(SAMPLE);

    @Test
    @DisplayName("counter 按基名可取（_total 后缀由解析器认，不要求调用方知道）")
    void counterFoundByBaseName() {
        List<MetricSample> hits = PrometheusTextParser.byName(parsed.samples(), "marketing_audit_drained");
        assertEquals(1, hits.size(), "counter 的 _total 线必须能被基名取到");
        assertEquals("marketing_audit_drained_total", hits.get(0).name());
        assertEquals(1.0, hits.get(0).value());
    }

    @Test
    @DisplayName("同名多条按 tag 分别取：维度不能被折叠成一个数")
    void tagsAreSeparateSamples() {
        List<MetricSample> hits = PrometheusTextParser.byName(parsed.samples(), "mkt_job_dedup_skipped");
        assertEquals(2, hits.size());
        assertEquals(3.0, PrometheusTextParser.valueOf(hits, "task", "local-message-retry"));
        assertEquals(1.0, PrometheusTextParser.valueOf(hits, "task", "seckill-timeout"));
    }

    @Test
    @DisplayName("tag 值里的逗号在引号内，不能当分隔符")
    void commasInsideTagValues() {
        List<MetricSample> hits = PrometheusTextParser.byName(parsed.samples(), "http_server_requests_seconds");
        assertEquals(1, hits.size());
        assertEquals(Map.of("method", "POST", "uri", "/api/coupon/grant",
                "status", "200", "outcome", "SUCCESS"), hits.get(0).tags());
        assertEquals(12.0, hits.get(0).value());
    }

    @Test
    @DisplayName("timer 的三条派生线各自独立，后缀原样保留")
    void timerDerivedLinesKeptApart() {
        List<MetricSample> hits = PrometheusTextParser.byName(parsed.samples(), "mkt_discount_calc_seconds");
        assertEquals(3, hits.size(), "count/sum/max 是三条不同的线，合起来就是错的");
        assertEquals(0.0042, valueOfName(hits, "mkt_discount_calc_seconds_sum"));
        assertEquals(0.0011, valueOfName(hits, "mkt_discount_calc_seconds_max"));
        assertEquals(9.0, valueOfName(hits, "mkt_discount_calc_seconds_count"));
    }

    @Test
    @DisplayName("注释行永远不是样本，哪怕它长得像")
    void helpAndTypeLinesAreNeverSamples() {
        assertTrue(PrometheusTextParser.byName(parsed.samples(), "marketing_audit_drained").stream()
                .noneMatch(s -> s.name().startsWith("#")));
        assertEquals(8, parsed.samples().size(), "样本条数写死：多一条就是注释被当成了数据");
    }

    @Test
    @DisplayName("tag 列表尾部多一个逗号也要认：micrometer 1.12 就是这么打的")
    void trailingCommaAfterLastTag() {
        PrometheusTextParser.ParseResult p = PrometheusTextParser.parse(
                "mkt_job_dedup_skipped_total{task=\"seckill-timeout\",} 2.0");
        assertEquals(0, p.malformedLines());
        assertEquals(2.0, PrometheusTextParser.valueOf(
                PrometheusTextParser.byName(p.samples(), "mkt_job_dedup_skipped"),
                "task", "seckill-timeout"));
    }

    @Test
    @DisplayName("坏行跳过并计数，不抛：一个进程多打一行怪东西不能让整张大盘消失")
    void malformedLinesAreCountedNotThrown() {
        assertEquals(2, parsed.malformedLines());
    }

    @Test
    @DisplayName("application tag 被摘掉：它是来源标识，不是维度")
    void applicationTagStripped() {
        List<MetricSample> hits = PrometheusTextParser.byName(parsed.samples(), "marketing_config_snapshot_version");
        assertEquals(1, hits.size());
        assertFalse(hits.get(0).tags().containsKey("application"),
                "留着 application 会让\"同一个指标按进程分组\"和\"按 target 分组\"重复计数");
        assertEquals(7.0, hits.get(0).value());
    }

    @Test
    @DisplayName("名字对不上 = 空列表，不是 0")
    void unknownNameIsMissingNotZero() {
        assertTrue(PrometheusTextParser.byName(parsed.samples(), "never_emitted_anywhere").isEmpty());
    }

    @Test
    @DisplayName("前缀匹配不吃进别人的名字：budget_seconds 不能命中 budget_seconds_extra")
    void prefixDoesNotSwallowUnrelatedNames() {
        PrometheusTextParser.ParseResult p = PrometheusTextParser.parse(
                "a_b_seconds_total 1.0\na_b_seconds_extra 2.0\na_b_seconds{v=\"x\"} 3.0\n");
        List<MetricSample> hits = PrometheusTextParser.byName(p.samples(), "a_b_seconds");
        assertEquals(2, hits.size(), "只应命中 _total 与带 tag 的那两条");
        assertTrue(hits.stream().noneMatch(s -> s.name().equals("a_b_seconds_extra")));
    }

    @Test
    @DisplayName("空文本与全注释文本都是\"零样本\"而不是异常")
    void emptyInputIsNotAnError() {
        assertEquals(0, PrometheusTextParser.parse("").samples().size());
        assertEquals(0, PrometheusTextParser.parse("# nothing here\n").samples().size());
    }

    private static double valueOfName(List<MetricSample> samples, String name) {
        return samples.stream().filter(s -> s.name().equals(name)).mapToDouble(MetricSample::value).sum();
    }
}
