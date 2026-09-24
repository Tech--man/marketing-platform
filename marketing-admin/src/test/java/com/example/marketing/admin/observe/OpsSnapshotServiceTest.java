package com.example.marketing.admin.observe;

import com.example.marketing.admin.config.OpsProperties;
import com.example.marketing.common.config.ConfigValues;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 快照组装的诚实性：一个 target 挂掉时这张盘该长什么样。
 *
 * <p>最要紧的一条：<b>不能整片 500，也不能整片变空</b>。前者让运维在最需要读数的时候什么都看不到，
 * 后者更坏——一张"什么都没发生"的大盘会被读成"一切正常"。</p>
 */
class OpsSnapshotServiceTest {

    private static final Instant WHEN = Instant.parse("2026-09-24T00:00:00Z");

    private final MetricSource metrics = mock(MetricSource.class);
    private final BacklogStore backlogStore = mock(BacklogStore.class);
    private final StreamDepth streamDepth = mock(StreamDepth.class);
    private final AuditTableStore auditStore = mock(AuditTableStore.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final OpsProperties properties = new OpsProperties();
    private OpsSnapshotService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.hasKey(anyString())).thenReturn(true);
        when(redis.getExpire(anyString(), any())).thenReturn(42L);
        properties.setMetricsMode(OpsProperties.MODE_PROXY);
        properties.setTargets(Map.of(
                "marketing-activity", "marketing-activity:8081",
                "marketing-coupon", "marketing-coupon:8082"));
        when(metrics.mode()).thenReturn("proxy");
        when(backlogStore.backlog()).thenReturn(List.of(
                new SchemaBacklog("marketing", Map.of("PENDING", 3L), 3L, null, List.of(), null)));
        when(streamDepth.backlog()).thenReturn(List.of(
                new StreamDepth.Depth("mkt:audit:pending", "admin-drain", 0L, 0L, true, "", null)));
        when(auditStore.stats()).thenReturn(new OpsSnapshotView.AuditTableView(
                1234L, "2026-09-20T00:00:00", List.of(Map.of("action", "cache.reheat", "n", 40L)), null));
        service = new OpsSnapshotService(metrics, backlogStore, streamDepth, auditStore, redis,
                properties, ConfigValues.empty(), "FULL", Clock.fixed(WHEN, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("全部可读：mode/时刻/逐 target 状态都在")
    void allTargetsOk() {
        when(metrics.scrape("marketing-activity")).thenReturn(parse(
                "marketing_cache_consistency{type=\"budget\"} 0.0",
                "coupon_grant_sold_out_total 7.0",
                "jvm_memory_used_bytes{area=\"heap\"} 1234.0"));
        when(metrics.scrape("marketing-coupon")).thenReturn(parse(
                "marketing_cache_consistency{type=\"coupon-stock\"} 2.0"));

        OpsSnapshotView view = service.snapshot();

        assertEquals("proxy", view.mode());
        assertEquals(WHEN, view.takenAt());
        assertEquals(List.of("marketing-activity", "marketing-coupon"),
                view.targets().stream().map(OpsSnapshotView.TargetView::name).toList(), "按名字稳定排序");
        assertTrue(view.targets().stream().allMatch(t -> "OK".equals(t.status())));
        assertEquals(Map.of("budget", 0L, "coupon-stock", 2L),
                view.consistency().stream().collect(java.util.stream.Collectors.toMap(
                        OpsSnapshotView.ConsistencyView::type, OpsSnapshotView.ConsistencyView::mismatch)),
                "两个模块的自检都要搬上来");
    }

    @Test
    @DisplayName("白名单外的指标进不了视图，白名单内的 counter 认 _total 线")
    void whitelistFiltersAndAcceptsDerivedLines() {
        when(metrics.scrape(anyString())).thenReturn(parse(
                "coupon_grant_sold_out_total 7.0", "jvm_memory_used_bytes{area=\"heap\"} 1234.0"));

        OpsSnapshotView view = service.snapshot();

        assertEquals(2, view.metrics().size(), "两个 target 各一条，杂项指标不进视图");
        assertTrue(view.metrics().stream().allMatch(m -> "coupon.grant.sold_out".equals(m.name())),
                view.metrics().toString());
        assertTrue(view.metrics().stream().allMatch(m -> m.value() == 7.0));
    }

    @Test
    @DisplayName("一个 target 抓不到：它自己是 ERROR + 原因，其余照常")
    void oneTargetFailureDoesNotSinkThePanel() {
        when(metrics.scrape("marketing-activity")).thenReturn(parse("mkt_job_dedup_skipped_total{task=\"seckill-timeout\"} 1.0"));
        when(metrics.scrape("marketing-coupon")).thenThrow(new ScrapeException("HTTP 500 from marketing-coupon:8082"));

        OpsSnapshotView view = service.snapshot();

        OpsSnapshotView.TargetView broken = view.targets().stream()
                .filter(t -> t.name().equals("marketing-coupon")).findFirst().orElseThrow();
        assertEquals("ERROR", broken.status());
        assertEquals("HTTP 500 from marketing-coupon:8082", broken.error());
        assertEquals(0, broken.sampleCount(), "失败时样本数当然是 0，但状态是 ERROR 而不是 OK(0)");
        assertEquals("OK", view.targets().stream().filter(t -> t.name().equals("marketing-activity"))
                .findFirst().orElseThrow().status());
        assertEquals(1234L, view.audit().rows(), "审计统计与抓取无关，照常给");
        assertTrue(view.notes().stream().anyMatch(n -> n.contains("1 个 target 抓取失败")),
                "notes 要说出这件事: " + view.notes());
    }

    @Test
    @DisplayName("任一来源读不到时总积压是 -1，不是把读到的几项加成\"看起来没事\"")
    void unknownBacklogStaysUnknown() {
        when(metrics.scrape(anyString())).thenReturn(parse());
        when(backlogStore.backlog()).thenReturn(List.of(
                new SchemaBacklog("marketing", Map.of("PENDING", 3L), 3L, null, List.of(), null),
                new SchemaBacklog("marketing_seckill", Map.of(), -1L, null, List.of(),
                        "PermissionDeniedDataAccessException: denied")));

        OpsSnapshotView view = service.snapshot();

        assertEquals(-1L, view.backlog().totalPendingSent());
        assertEquals(2, view.backlog().schemas().size(), "读不到的那个库也要出现在清单里");
        assertNotNull(view.backlog().schemas().get(1).error());
    }

    @Test
    @DisplayName("自检的 -1 原样搬上来并带说明，不被压成\"0 项不符\"")
    void unknownConsistencyIsNotZero() {
        when(metrics.scrape(anyString())).thenReturn(parse(
                "marketing_cache_consistency{type=\"budget\"} -1.0"));

        OpsSnapshotView view = service.snapshot();

        assertEquals(-1L, view.consistency().get(0).mismatch());
        assertTrue(view.consistency().get(0).note().contains("判定不了"),
                view.consistency().get(0).note());
    }

    @Test
    @DisplayName("租约读数：键在就是有人持有，TTL 与持有者前缀一起给")
    void leaseReadout() {
        when(metrics.scrape(anyString())).thenReturn(parse());
        when(values.get("mkt:job:seckill-timeout")).thenReturn("abcdef12-3456");
        when(values.get("mkt:job:coupon-expire")).thenReturn(null);

        OpsSnapshotView view = service.snapshot();

        OpsSnapshotView.JobLeaseView held = view.liveness().jobs().stream()
                .filter(j -> j.task().equals("seckill-timeout")).findFirst().orElseThrow();
        assertTrue(held.held());
        assertEquals(42L, held.ttlLeft());
        assertEquals("abcdef12", held.holder());
        assertTrue(!view.liveness().jobs().stream()
                .filter(j -> j.task().equals("coupon-expire")).findFirst().orElseThrow().held());
    }

    @Test
    @DisplayName("聚合形态下模块名不参与存活判定：不适用 ≠ 没在跑（两档各验一次）")
    void aggregatedFormFlipsWhichNamesApply() {
        when(metrics.scrape(anyString())).thenReturn(parse());
        OpsSnapshotService lite = new OpsSnapshotService(metrics, backlogStore, streamDepth, auditStore,
                redis, properties, ConfigValues.empty(), "LITE", Clock.fixed(WHEN, ZoneOffset.UTC));

        Map<String, Boolean> inLite = lite.snapshot().liveness().processes();
        assertEquals(null, inLite.get("marketing-activity"),
                "LITE 里它就在 standalone 这个 JVM 内，报 false 等于谎报五个服务全死");
        assertEquals(Boolean.TRUE, inLite.get("marketing-standalone"), "聚合进程自己该被读到");

        Map<String, Boolean> inFull = service.snapshot().liveness().processes();
        assertEquals(null, inFull.get("marketing-standalone"), "分进程档里 standalone 不存在");
        assertEquals(Boolean.TRUE, inFull.get("marketing-activity"), "分进程档里模块自己上报");
        assertTrue(inFull.values().stream().anyMatch(v -> v == null), "两档都要有\"不适用\"这一态");
    }

    private static PrometheusTextParser.ParseResult parse(String... lines) {
        return PrometheusTextParser.parse(String.join("\n", lines));
    }
}
