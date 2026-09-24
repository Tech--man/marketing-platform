package com.example.marketing.admin.observe;

import com.example.marketing.admin.config.OpsProperties;
import com.example.marketing.common.config.ConfigForm;
import com.example.marketing.common.config.ConfigKeys;
import com.example.marketing.common.config.ConfigValues;
import com.example.marketing.common.schedule.RedisLeaseLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 组装一次只读快照。
 *
 * <p>三条塑造了这个类的约束：</p>
 * <ol>
 *   <li><b>一个 target 失败不能让整张盘失败</b>：抓不到的那条记成 {@code ERROR + 原因}，
 *       其余照常返回。运维最需要的是"别的都正常，只有这个读不到"这句话；</li>
 *   <li><b>串行、单次、不驻留</b>：抓取只在有人点的时候发生（不是常驻轮询），
 *       LITE 那台弱主机才不会被自己的观测面拖慢；</li>
 *   <li><b>只取白名单指标</b>：解析后的名字进不了视图就是不存在——顺手把别人加的调试指标
 *       全贴出来，等于把内部实现细节变成对外契约。</li>
 * </ol>
 */
@Slf4j
@Service
public class OpsSnapshotService {

    /** ④ 认的指标名（Micrometer 侧的写法，点号即可；解析器会做下划线归一） */
    static final Set<String> METRIC_WHITELIST = Set.of(
            "marketing.cache.consistency",
            "marketing.config.snapshot.version", "marketing.config.degraded.size",
            "marketing.config.entries.foreign", "marketing.config.poll.error",
            "marketing.config.entry.ignored",
            "marketing.audit.drained", "marketing.audit.drain.skipped", "marketing.audit.drain.error",
            "marketing.audit.outbox.error",
            "marketing.reheat.executed", "marketing.reheat.exec.failed",
            "marketing.reheat.dispatch.error", "marketing.reheat.dispatch.skipped",
            "mkt.job.dedup_skipped",
            "mkt.discount.degraded", "mkt.discount.calc.seconds",
            "marketing.gateway.rate.limit.rejected",
            "coupon.grant.accepted", "coupon.grant.sold_out", "coupon.grant.persisted",
            "coupon.grant.risk.rejected",
            "seckill.grab.accepted", "seckill.grab.risk_rejected",
            "seckill.order.persisted", "seckill.order.timeout_cancelled", "seckill.order.inflight_duplicate");

    /**
     * 线格式名 → 白名单基名。counter 的 {@code _total} 与 timer 的 {@code _count/_sum/_max}
     * 都要能在这一张表里查到，否则白名单会静默地把这些线挡在外面（挡在外面的表现是
     * "这个指标不存在"，不是"被过滤了"）。
     */
    private static final Map<String, String> BASE_BY_NAME = buildBaseIndex();

    private static Map<String, String> buildBaseIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        for (String base : METRIC_WHITELIST) {
            String underscored = base.replace('.', '_');
            index.put(underscored, base);
            index.put(underscored + "_total", base);
            for (String suffix : List.of("count", "sum", "max", "bucket", "created")) {
                index.put(underscored + "_" + suffix, base);
            }
        }
        return Map.copyOf(index);
    }

    /** 各进程的自述键（TTL 180s、每 60s 重投）：在不在就是它有没有在跑 */
    static final List<String> PROCESSES = List.of(
            "marketing-activity", "marketing-coupon", "marketing-discount", "marketing-seckill",
            "marketing-admin", "marketing-gateway", "marketing-standalone");

    /**
     * LITE/dev 下这四个模块 + 后台都在 standalone 这一个 JVM 里，它们<b>不该</b>有自己的自述键
     * ——把它们报成 false，运维会以为五个服务全死了。这与 ⑤ 的"未上报"同源，
     * 但 ④ 是给人看健康度的那一面，所以这里把"不适用"与"没在跑"分开。
     */
    static final List<String> AGGREGATED_IN_STANDALONE = List.of(
            "marketing-activity", "marketing-coupon", "marketing-discount", "marketing-seckill",
            "marketing-admin");

    private final MetricSource metrics;
    private final BacklogStore backlogStore;
    private final StreamDepth streamDepth;
    private final AuditTableStore auditStore;
    private final StringRedisTemplate redis;
    private final OpsProperties properties;
    private final ConfigValues values;
    private final String deployForm;
    private final Clock clock;

    public OpsSnapshotService(MetricSource metrics, BacklogStore backlogStore, StreamDepth streamDepth,
                             AuditTableStore auditStore, StringRedisTemplate redis, OpsProperties properties,
                             ConfigValues values,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${marketing.config.form:${DEPLOY_FORM:}}") String deployForm,
                             Clock clock) {
        this.metrics = metrics;
        this.backlogStore = backlogStore;
        this.streamDepth = streamDepth;
        this.auditStore = auditStore;
        this.redis = redis;
        this.properties = properties;
        this.values = values;
        this.deployForm = deployForm;
        this.clock = clock;
    }

    public OpsSnapshotView snapshot() {
        List<OpsSnapshotView.TargetView> targets = new ArrayList<>();
        List<MetricEntry> scraped = new ArrayList<>();
        for (TargetRef ref : targetsOrdered()) {
            scrapeOne(ref, targets, scraped);
        }
        OpsSnapshotView.BacklogView backlog = backlog();
        return new OpsSnapshotView(metrics.mode(), ConfigForm.resolve(deployForm), Instant.now(clock),
                List.copyOf(targets), backlog, consistency(scraped), whitelist(scraped),
                liveness(), auditStore.stats(), notes(backlog, targets));
    }

    /** 按 target 名排序，两次读数的大屏长得一样，才看得出变化 */
    private List<TargetRef> targetsOrdered() {
        return OpsTargets.parseAll(properties.getTargets()).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue).toList();
    }

    /** 单个 target 的抓取。异常只影响这一条 */
    private void scrapeOne(TargetRef ref, List<OpsSnapshotView.TargetView> targets, List<MetricEntry> scraped) {
        try {
            PrometheusTextParser.ParseResult result = metrics.scrape(ref.name());
            targets.add(new OpsSnapshotView.TargetView(ref.name(), ref.url(), "OK", null,
                    result.samples().size(), result.malformedLines()));
            result.samples().forEach(sample -> scraped.add(new MetricEntry(ref.name(), sample)));
        } catch (ScrapeException e) {
            log.warn("[ops] target {} 抓取失败: {}", ref.name(), e.getMessage());
            targets.add(new OpsSnapshotView.TargetView(ref.name(), ref.url(), "ERROR", e.getMessage(), 0, 0));
        }
    }

    /** 一条未排空读数（-1 = 有来源读不到，此时总数不可信） */
    private OpsSnapshotView.BacklogView backlog() {
        List<SchemaBacklog> schemas = backlogStore.backlog();
        List<StreamDepth.Depth> streams = streamDepth.backlog();
        long total = 0L;
        for (SchemaBacklog s : schemas) {
            if (s.unknown()) {
                total = -1L;
                break;
            }
            total += s.pendingSent();
        }
        for (StreamDepth.Depth d : streams) {
            if (!d.applicable()) {
                continue;   // 不可见不参与总数，但也不会被当成 0：它自己带着 note
            }
            if (d.error() != null || d.len() < 0) {
                total = -1L;
                break;
            }
        }
        return new OpsSnapshotView.BacklogView(schemas, streams, total);
    }

    /** 一致性：判定在各模块自己注册的 gauge 里，这里只是把它搬到视图上 */
    private List<OpsSnapshotView.ConsistencyView> consistency(List<MetricEntry> scraped) {
        List<OpsSnapshotView.ConsistencyView> out = new ArrayList<>();
        for (MetricEntry entry : scraped) {
            String base = BASE_BY_NAME.get(entry.sample().name());
            if (!"marketing.cache.consistency".equals(base)) {
                continue;
            }
            out.add(new OpsSnapshotView.ConsistencyView(entry.target(), entry.sample().tag("type"),
                    (long) entry.sample().value(),
                    entry.sample().value() < 0 ? "该模块判定不了（Redis 不可达等）"
                            : entry.sample().value() > 0
                            ? "抽样内有不符项，可用 POST /api/admin/cache/reheat 修正" : ""));
        }
        return List.copyOf(out);
    }

    private List<OpsSnapshotView.MetricView> whitelist(List<MetricEntry> scraped) {
        List<OpsSnapshotView.MetricView> out = new ArrayList<>();
        for (MetricEntry entry : scraped) {
            String base = BASE_BY_NAME.get(entry.sample().name());
            if (base != null) {
                out.add(new OpsSnapshotView.MetricView(entry.target(), base,
                        entry.sample().tags(), entry.sample().value()));
            }
        }
        return List.copyOf(out);
    }

    private OpsSnapshotView.LivenessView liveness() {
        boolean aggregated = isAggregatedForm();
        Map<String, Boolean> processes = new LinkedHashMap<>();
        for (String process : PROCESSES) {
            boolean notApplicable = aggregated
                    ? AGGREGATED_IN_STANDALONE.contains(process)
                    : "marketing-standalone".equals(process);
            if (notApplicable) {
                // 本形态下这个进程名根本不存在：null=不适用，区别于 false=该在而没在
                processes.put(process, null);
                continue;
            }
            try {
                processes.put(process, Boolean.TRUE.equals(redis.hasKey(ConfigKeys.schema(process))));
            } catch (RuntimeException e) {
                processes.put(process, null);   // null = 不知道（区别于 false = 不在）
            }
        }
        List<OpsSnapshotView.JobLeaseView> jobs = new ArrayList<>();
        for (String task : properties.getScheduledTasks()) {
            jobs.add(lease(task));
        }
        return new OpsSnapshotView.LivenessView(processes, List.copyOf(jobs));
    }

    private OpsSnapshotView.JobLeaseView lease(String task) {
        String key = RedisLeaseLock.keyOf(task);
        try {
            String holder = redis.opsForValue().get(key);
            Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
            return new OpsSnapshotView.JobLeaseView(task, holder != null,
                    ttl == null ? -1L : ttl, holder == null ? "" : holder.substring(0, Math.min(8, holder.length())));
        } catch (RuntimeException e) {
            return new OpsSnapshotView.JobLeaseView(task, false, -1L, "");
        }
    }

    /** LITE/dev 是聚合形态；FULL 是分进程。判据就是 ⑤ 那套 DEPLOY_FORM，不另起一份 */
    private boolean isAggregatedForm() {
        String form = ConfigForm.resolve(deployForm);
        return "LITE".equals(form) || "DEV".equals(form);
    }

    private List<String> notes(OpsSnapshotView.BacklogView backlog,
                               List<OpsSnapshotView.TargetView> targets) {
        List<String> notes = new ArrayList<>();
        notes.add("指标源模式 " + metrics.mode() + "：LITE/dev 读本 JVM，FULL 抓各进程。"
                + "被点的 target 抓不到时那一条是 ERROR，不是 0。");
        notes.add("积压与一致性都是抽样/瞬时读数；-1 一律表示\"判定不了\"，不等于 0。");
        notes.add(isAggregatedForm()
                ? "本档是聚合形态：四个业务模块与后台都在 standalone 里，"
                        + "所以它们的进程名不参与存活判定（不适用 ≠ 没在跑）。"
                : "本档分进程：marketing-standalone 不适用，六个进程各自上报。");
        if (backlog.totalPendingSent() < 0) {
            notes.add("总积压未知：至少有一个来源读不到（见 schemas/streams 里带 error 的那条）");
        }
        long failed = targets.stream().filter(t -> "ERROR".equals(t.status())).count();
        if (failed > 0) {
            notes.add(failed + " 个 target 抓取失败，其余读数照常");
        }
        if (values.appliedVersion() <= 0) {
            notes.add("本进程没有应用过任何在线配置覆盖（版本 0）");
        }
        return List.copyOf(notes);
    }

    /** 抓取过程中的一条样本（带上它是从哪个 target 来的） */
    private record MetricEntry(String target, MetricSample sample) {
    }
}
