package com.example.marketing.admin.dto;

import com.example.marketing.common.cache.CacheReheater;
import com.example.marketing.common.reheat.ReheatPayloads;

/**
 * 一次重预热的结果。
 *
 * <p>三种状态的含义刻意不同，因为运营该做的动作不一样：</p>
 * <ul>
 *   <li>{@code DONE} —— 本进程就有权执行并且执行完了（LITE 聚合形态走这条）；</li>
 *   <li>{@code DISPATCHED} —— 已投给 owning 服务，回执还没回来（{@code id} 拿去查
 *       {@code GET /api/admin/cache/reheat/ack}）；</li>
 *   <li>{@code FAILED} —— owning 服务执行时抛了，{@code error} 里带原因。</li>
 * </ul>
 *
 * <p>{@code note} 是人读的一句话，别让前端去猜状态机。</p>
 */
public record ReheatReceipt(
        String status, String type, String key, String id,
        long before, long after, String error, String note) {

    public static ReheatReceipt done(CacheReheater.Result r) {
        return new ReheatReceipt("DONE", r.type(), r.key(), "", r.before(), r.after(), "",
                "本进程已执行：" + r.formula());
    }

    public static ReheatReceipt dispatched(String id, String type, String key, boolean force) {
        return new ReheatReceipt("DISPATCHED", type, key, id, -1L, -1L, "",
                "已投递给 owning 服务（force=" + force + "），用 id=" + id + " 查回执");
    }

    public static ReheatReceipt fromAck(ReheatPayloads.Ack ack) {
        return new ReheatReceipt(ack.status(), ack.type(), ack.key(), ack.id(),
                ack.before(), ack.after(), ack.error(),
                "DONE".equals(ack.status()) ? "owning 服务已执行完成" : "owning 服务执行失败");
    }

    public static ReheatReceipt stillQueued(String id, String type, String key) {
        return new ReheatReceipt("DISPATCHED", type, key, id, -1L, -1L, "",
                "owning 服务还没消费这条（队列积压，或它已停止）");
    }

    public static ReheatReceipt unknown(String id, String type) {
        return new ReheatReceipt("UNKNOWN", type, "", id, -1L, -1L, "",
                "没有这个 id 的任何痕迹：未投递过，或回执与投递标记都已过期（10 分钟）");
    }
}
