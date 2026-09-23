package com.example.marketing.common.transport;

import java.time.Duration;

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

    /**
     * 重预热的待执行流——<b>按 type 分键</b>，而不是所有类型共用一条。
     *
     * <p>共用一条 + 共用一个消费组的话，Redis 会把消息投给组里<b>任意</b>一个消费者：
     * "budget" 的请求可能落到 seckill 进程手里，它没有这个 reheater，只能失败或空 ACK，
     * 真正的 owner 反而永远看不到。分键之后"谁能消费"就是结构决定的，不靠约定。</p>
     */
    public static String reheatPending(String type) {
        return "mkt:reheat:" + type + ":pending";
    }

    /** 执行回执（String + TTL，按 id 查） */
    public static String reheatAck(String type, String id) {
        return "mkt:reheat:" + type + ":ack:" + id;
    }

    /**
     * "已投递"标记：回执键不在 + 这个在 = 还在队列里（DISPATCHED）；
     * 两个都不在 = UNKNOWN。没有它就只能把"还没执行"和"这个 id 根本不存在"混为一谈。
     * 值存的是当初那个缓存 key，好让查询端点不必让调用方把参数再传一遍。
     */
    public static String reheatSent(String type, String id) {
        return "mkt:reheat:" + type + ":sent:" + id;
    }

    /** 回执与"已投递"标记的存活时间：刷缓存是一次性动作，超 10 分钟没人问就不必再占内存 */
    public static final Duration REHEAT_RECEIPT_TTL = Duration.ofMinutes(10);

    /** 回执 id 的发号器：与 ⑤ 的配置版本号同源（INCR，不用 MAX+1） */
    public static final String REHEAT_SEQUENCE = "mkt:reheat:seq";

    private StreamKeys() {
    }
}
