package com.example.marketing.common.idempotent;

import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 通用幂等执行器：业务唯一键 + 去重表 + 状态机流转。
 *
 * <p>语义（至少一次投递下的"精确一次"业务效果）：</p>
 * <ul>
 *   <li>首次请求：抢占 PROCESSING → 执行业务 → 回写 SUCCESS(缓存结果) / FAILED；</li>
 *   <li>重复请求且已成功：直接返回缓存结果（回放）；</li>
 *   <li>重复请求且处理中（租约内）：抛 {@link ErrorCode#DUPLICATE_REQUEST}，由调用方轮询结果；PROCESSING 停留超过租约（默认 120s）视为执行者已死，重试可接管（H11）——否则一次非优雅停机会把键永久砖化在「处理中」；</li>
 *   <li>上次失败：允许重新抢占执行。</li>
 * </ul>
 *
 * <p>依赖表 idempotent_record（biz_key 唯一索引），DDL 见 docker/mysql/init.sql。</p>
 */
@Slf4j
public class IdempotentExecutor {

    private static final String TABLE = "idempotent_record";
    /** 默认租约：远大于业务动作的正常耗时（秒杀/领券毫秒级），足够区分「在执行」与「已死」 */
    private static final long DEFAULT_PROCESSING_LEASE_SECONDS = 120;
    /** Void 结果的占位 JSON，避免空串与"未写入"歧义 */
    private static final String VOID_RESULT = "{}";

    private final JdbcTemplate jdbcTemplate;

    /**
     * PROCESSING 租约（H11，2026-09-29 架构审查）：键停在 PROCESSING 超过该秒数，
     * 视为上一个执行者已死（进程崩溃 / OOM 时 catch 不到 RuntimeException），
     * 允许重抢占。此前没有租约——一次非优雅停机就会留下随机数量的死键，
     * 同一 requestId 的所有重试永远吃 DUPLICATE_REQUEST，只能人工改库。
     */
    private final long processingLeaseSeconds;

    public IdempotentExecutor(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, DEFAULT_PROCESSING_LEASE_SECONDS);
    }

    public IdempotentExecutor(JdbcTemplate jdbcTemplate, long processingLeaseSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.processingLeaseSeconds = processingLeaseSeconds;
    }

    /**
     * 幂等执行有返回值的业务动作。
     *
     * @param bizKey     业务唯一键（如 requestId / activityNo+userId+templateId）
     * @param resultType 结果类型，用于回放时反序列化
     * @param action     真实业务逻辑（仅在成功抢占后执行）
     */
    public <T> T execute(String bizKey, Class<T> resultType, Supplier<T> action) {
        ClaimResult claim = claim(bizKey);
        switch (claim) {
            case REPLAY_SUCCESS:
                return JsonUtils.parse(loadResultJson(bizKey), resultType);
            case REPLAY_PROCESSING:
                throw BizException.of(ErrorCode.DUPLICATE_REQUEST);
            case ACQUIRED:
            default:
                break;
        }
        try {
            T result = action.get();
            String json = resultType == Void.class ? VOID_RESULT : JsonUtils.toJson(result);
            // P2（2026-09-30 第二轮复审）：markSuccess 自身的失败（result_json 超 TEXT、
            // DB 超时/死锁）绝不落 markFailed——action 已经成功（预扣/消息都完成了），
            // 标 FAILED 会把"已成功的动作"判成可重试，客户端重试整体重跑 action（对
            // 领券就是二次预扣）。留在 PROCESSING 让租约接管路径处理（接管者重执行或
            // 回放，状态机是安全的）；本请求照常把已拿到的结果还给调用方。
            try {
                markSuccess(bizKey, json);
            } catch (RuntimeException markEx) {
                log.error("[idempotent] markSuccess 写库失败（action 已成功，保留 PROCESSING 交租约接管）"
                        + " bizKey={}: {}", bizKey, markEx.toString());
            }
            return result;
        } catch (RuntimeException e) {
            markFailed(bizKey, e.getMessage());
            throw e;
        }
    }

    /**
     * 幂等执行无返回值的业务动作。
     */
    public void executeVoid(String bizKey, Runnable action) {
        execute(bizKey, Void.class, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 查询某业务键是否已成功完成（供结果查询接口使用）。
     */
    public boolean isDone(String bizKey) {
        List<String> status = jdbcTemplate.queryForList(
                "SELECT status FROM " + TABLE + " WHERE biz_key = ?", String.class, bizKey);
        return !status.isEmpty() && IdempotentStatus.SUCCESS.name().equals(status.get(0));
    }

    // ---------------- private ----------------

    private enum ClaimResult { ACQUIRED, REPLAY_SUCCESS, REPLAY_PROCESSING }

    private ClaimResult claim(String bizKey) {
        StateRow row = queryState(bizKey);
        if (row == null) {
            try {
                jdbcTemplate.update("INSERT INTO " + TABLE + " (biz_key, status) VALUES (?, ?)",
                        bizKey, IdempotentStatus.PROCESSING.name());
                return ClaimResult.ACQUIRED;
            } catch (DuplicateKeyException e) {
                // 并发竞争失败：以先插入者为准，重读状态
                return claim(bizKey);
            }
        }
        if (IdempotentStatus.SUCCESS.name().equals(row.status())) {
            return ClaimResult.REPLAY_SUCCESS;
        }
        if (IdempotentStatus.PROCESSING.name().equals(row.status())) {
            // H11：PROCESSING 停留超过租约 → 上一个执行者已死，CAS 抢占（比对读到的
            // update_time，抢到即接管；并发下只有一个人能成）。fresh 的 PROCESSING
            // 才是真正的「处理中」，继续抛 DUPLICATE_REQUEST 让调用方轮询。
            if (isLeaseExpired(row.updateTime())) {
                int taken = jdbcTemplate.update(
                        "UPDATE " + TABLE + " SET status = ?, update_time = CURRENT_TIMESTAMP "
                                + "WHERE biz_key = ? AND status = ? AND update_time = ?",
                        IdempotentStatus.PROCESSING.name(), bizKey,
                        IdempotentStatus.PROCESSING.name(), row.updateTime());
                if (taken == 1) {
                    log.warn("[idempotent] 抢占过期 PROCESSING 租约 bizKey={}, 停留超过 {}s",
                            bizKey, processingLeaseSeconds);
                    return ClaimResult.ACQUIRED;
                }
            }
            return ClaimResult.REPLAY_PROCESSING;
        }
        // FAILED：条件更新抢占，抢到返回执行，没抢到按处理中处理。
        // update_time 显式推进：MySQL 有 ON UPDATE 兜底，H2 没有——不显式写，
        // 租约判定在 H2 测试里会把刚抢占的 FAILED 又当成过期 PROCESSING。
        int taken = jdbcTemplate.update(
                "UPDATE " + TABLE + " SET status = ?, update_time = CURRENT_TIMESTAMP "
                        + "WHERE biz_key = ? AND status = ?",
                IdempotentStatus.PROCESSING.name(), bizKey, IdempotentStatus.FAILED.name());
        return taken == 1 ? ClaimResult.ACQUIRED : ClaimResult.REPLAY_PROCESSING;
    }

    /** 租约判定。update_time 由 DB 时钟写入，这里用应用时钟比对——同机部署偏差远小于
     *  租约量级（120s），可接受；跨机时钟漂移超过租约的部署不该存在。 */
    private boolean isLeaseExpired(java.sql.Timestamp lastTransition) {
        if (lastTransition == null) {
            return false; // 读不到时间戳时宁可按「处理中」拒绝，也不误抢一个可能活着的执行
        }
        long leaseMillis = processingLeaseSeconds * 1000L;
        return System.currentTimeMillis() - lastTransition.getTime() > leaseMillis;
    }

    private StateRow queryState(String bizKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status, update_time FROM " + TABLE + " WHERE biz_key = ?", bizKey);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        Object time = row.get("update_time");
        return new StateRow((String) row.get("status"),
                time instanceof java.sql.Timestamp ts ? ts : null);
    }

    private record StateRow(String status, java.sql.Timestamp updateTime) {
    }

    private String loadResultJson(String bizKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT result_json FROM " + TABLE + " WHERE biz_key = ?", bizKey);
        if (rows.isEmpty() || rows.get(0).get("result_json") == null) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR);
        }
        return (String) rows.get(0).get("result_json");
    }

    private void markSuccess(String bizKey, String resultJson) {
        int updated = jdbcTemplate.update(
                "UPDATE " + TABLE + " SET status = ?, result_json = ?, update_time = CURRENT_TIMESTAMP "
                        + "WHERE biz_key = ? AND status = ?",
                IdempotentStatus.SUCCESS.name(), resultJson, bizKey, IdempotentStatus.PROCESSING.name());
        if (updated == 0) {
            log.warn("[idempotent] markSuccess 未命中 PROCESSING, bizKey={}", bizKey);
        }
    }

    private void markFailed(String bizKey, String errorMsg) {
        jdbcTemplate.update(
                "UPDATE " + TABLE + " SET status = ?, error_msg = ?, update_time = CURRENT_TIMESTAMP "
                        + "WHERE biz_key = ? AND status = ?",
                IdempotentStatus.FAILED.name(), truncate(errorMsg), bizKey, IdempotentStatus.PROCESSING.name());
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
