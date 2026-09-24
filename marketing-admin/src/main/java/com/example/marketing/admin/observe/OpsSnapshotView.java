package com.example.marketing.admin.observe;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * ④ 的唯一出参：一张只读快照。
 *
 * <p>形状上有一条贯穿的纪律 —— <b>每个可能"读不到"的地方都要能说出自己读不到</b>：
 * target 有 {@code status=ERROR}，积压有 {@code -1}，一致性有 {@code UNKNOWN}，
 * 整体降级有 {@code notes}。一张缺了某个进程但看着完全正常的大盘，比一行报错更坏。</p>
 */
public record OpsSnapshotView(
        /** 本次快照用到的源：{@code local} / {@code proxy} / {@code local+proxy} */
        String mode,
        String ownForm,
        Instant takenAt,
        List<TargetView> targets,
        BacklogView backlog,
        List<ConsistencyView> consistency,
        List<MetricView> metrics,
        LivenessView liveness,
        AuditTableView audit,
        List<String> notes) {

    /**
     * 一个进程的抓取结果。{@code status} 取 {@code OK | ERROR}；抓取失败时样本数为 0
     * <b>并且</b> error 非空，两件事一起才不构成误导。{@code source} 是这一条实际走的路
     * （{@code local} 读本 JVM / {@code proxy} 走 HTTP）——同一次快照里两者可以并存。
     */
    public record TargetView(String name, String url, String source, String status, String error,
                             int sampleCount, int malformedLines) {
    }

    /** 未排空条数合计；-1 = 有任何一个来源读不到，此时不能报一个看似健康的总数 */
    public record BacklogView(List<SchemaBacklog> schemas, List<StreamDepth.Depth> streams,
                              long totalPendingSent) {
    }

    /**
     * 缓存与账的一致性自检结果（判定长在 owning 模块，这里只是把它的读数搬上来）。
     *
     * @param mismatch 抽样内不符条数；-1 = 该来源判定不了
     */
    public record ConsistencyView(String target, String type, long mismatch, String note) {
    }

    /** 白名单内的一条指标（已按 name + tags 去重，不再带原始文本） */
    public record MetricView(String target, String name, Map<String, String> tags, double value) {
    }

    /**
     * 进程存活与周期任务租约。
     *
     * @param processes 配置里的进程名 → 它的自述键是否还在（TTL 180s、每 60s 重投，不在就是没在跑）
     * @param jobs      三个周期任务的租约键读数
     */
    public record LivenessView(Map<String, Boolean> processes, List<JobLeaseView> jobs) {
    }

    /**
     * @param held     此刻是否被某个实例持有（键在）
     * @param ttlLeft  租约剩余秒数；-1 = 读不到
     * @param holder   持有者标识（每 JVM 一个 UUID 的前 8 位）
     */
    public record JobLeaseView(String task, boolean held, long ttlLeft, String holder) {
    }

    /** 审计表自身：行数、最老一条、按 action 的 top N */
    public record AuditTableView(long rows, String oldestAt, List<Map<String, Object>> topActions,
                                 String error) {
    }
}
