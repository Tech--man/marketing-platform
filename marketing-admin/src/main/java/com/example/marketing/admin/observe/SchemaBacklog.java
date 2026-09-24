package com.example.marketing.admin.observe;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 一个库的 {@code local_message} 积压快照。
 *
 * <p>逐库一条而不是一行合计：每服务一库档有四个库各持一份该表，并成一个数就看不出
 * "是哪个模块的排空堵了"。合计由上层视图给。</p>
 *
 * @param pendingSent    未排空条数；<b>-1 表示读不到</b>（权限/表缺失/查询失败）。
 *                       用 -1 而不是 0：0 是健康读数，把"看不见"报成 0 是本段最坏的一种错
 * @param statusCounts   按状态原样计数，不做白名单——加了新状态的人不该被静默吃掉
 * @param earliestRetryAt 未排空行里最早的 {@code next_retry_time}；null = 没有未排空行或读不到
 * @param deadLetters     FAILED 的样本（topic + bizKey），最多 5 条，供人直接去查
 * @param error           非 null 即这个库没读到，原因在此
 */
public record SchemaBacklog(String schema, Map<String, Long> statusCounts, long pendingSent,
                            LocalDateTime earliestRetryAt, List<String> deadLetters, String error) {

    /** 读不到时的未排空条数 */
    public static final long UNKNOWN = -1L;

    public boolean unknown() {
        return pendingSent == UNKNOWN;
    }
}
