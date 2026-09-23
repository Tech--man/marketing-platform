package com.example.marketing.common.transport;

/**
 * ③ 的两条跨进程 Stream 的键名，唯一允许出现这些字面量的地方。
 *
 * <p>为什么用 Stream 而不是 ⑤ 段内 spec §4 候选的 {@code LPUSH + LTRIM + TTL}：
 * TTL 淘汰等于<b>静默丢审计</b>，而"静默不一致"正是本项目最贵的一类 bug（⑤ 为版本号撞号
 * 立过同一条）。Stream 的 {@code XLEN} 与 PEL 能被 ④ 直接报成 pending 数，
 * Redis 被清空这件事也仍然看得见（键没了 vs 消费组没了，是两种诊断）。</p>
 */
public final class StreamKeys {

    /** 上限：admin 长时间不消费时丢最旧的，而不是把 Redis 撑爆 */
    public static final int MAX_LEN = 100_000;

    /** admin 侧 drain 用的消费组 */
    public static final String ADMIN_DRAIN_GROUP = "admin-drain";

    /** owning 服务执行重预热用的消费组 */
    public static final String OWNING_CONSUMER_GROUP = "owning-exec";

    public static String auditPending() {
        return "mkt:audit:pending";
    }

    public static String reheatPending() {
        return "mkt:reheat:pending";
    }

    public static String reheatAck() {
        return "mkt:reheat:ack";
    }

    private StreamKeys() {
    }
}
