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
 *   <li>重复请求且处理中：抛 {@link ErrorCode#DUPLICATE_REQUEST}，由调用方轮询结果；</li>
 *   <li>上次失败：允许重新抢占执行。</li>
 * </ul>
 *
 * <p>依赖表 idempotent_record（biz_key 唯一索引），DDL 见 docker/mysql/init.sql。</p>
 */
@Slf4j
public class IdempotentExecutor {

    private static final String TABLE = "idempotent_record";
    /** Void 结果的占位 JSON，避免空串与"未写入"歧义 */
    private static final String VOID_RESULT = "{}";

    private final JdbcTemplate jdbcTemplate;

    public IdempotentExecutor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
            markSuccess(bizKey, json);
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
        String status = queryStatus(bizKey);
        if (status == null) {
            try {
                jdbcTemplate.update("INSERT INTO " + TABLE + " (biz_key, status) VALUES (?, ?)",
                        bizKey, IdempotentStatus.PROCESSING.name());
                return ClaimResult.ACQUIRED;
            } catch (DuplicateKeyException e) {
                // 并发竞争失败：以先插入者为准，重读状态
                return claim(bizKey);
            }
        }
        if (IdempotentStatus.SUCCESS.name().equals(status)) {
            return ClaimResult.REPLAY_SUCCESS;
        }
        if (IdempotentStatus.PROCESSING.name().equals(status)) {
            return ClaimResult.REPLAY_PROCESSING;
        }
        // FAILED：条件更新抢占，抢到返回执行，没抢到按处理中处理
        int taken = jdbcTemplate.update("UPDATE " + TABLE + " SET status = ? WHERE biz_key = ? AND status = ?",
                IdempotentStatus.PROCESSING.name(), bizKey, IdempotentStatus.FAILED.name());
        return taken == 1 ? ClaimResult.ACQUIRED : ClaimResult.REPLAY_PROCESSING;
    }

    private String queryStatus(String bizKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status FROM " + TABLE + " WHERE biz_key = ?", bizKey);
        return rows.isEmpty() ? null : (String) rows.get(0).get("status");
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
                "UPDATE " + TABLE + " SET status = ?, result_json = ? WHERE biz_key = ? AND status = ?",
                IdempotentStatus.SUCCESS.name(), resultJson, bizKey, IdempotentStatus.PROCESSING.name());
        if (updated == 0) {
            log.warn("[idempotent] markSuccess 未命中 PROCESSING, bizKey={}", bizKey);
        }
    }

    private void markFailed(String bizKey, String errorMsg) {
        jdbcTemplate.update(
                "UPDATE " + TABLE + " SET status = ?, error_msg = ? WHERE biz_key = ? AND status = ?",
                IdempotentStatus.FAILED.name(), truncate(errorMsg), bizKey, IdempotentStatus.PROCESSING.name());
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
