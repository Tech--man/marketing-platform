package com.example.marketing.common.audit;

import com.example.marketing.common.transport.StreamKeys;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

/**
 * 业务侧的审计投递口：一条写完就把载荷 {@code XADD} 进 {@code mkt:audit:pending}，
 * 由 marketing-admin drain 落 {@code admin_audit_log}（段内 spec §4.1）。
 *
 * <p>刻意不给键设 TTL：TTL 淘汰等于静默丢审计。上界靠 {@code XTRIM MAXLEN}——
 * 丢的是最旧的条目，而不是整段过期，且 {@code XLEN} 在 ④ 里看得见。</p>
 *
 * <p>Redis 抛异常只 warn + 计数：审计失败绝不能把业务写回滚掉（admin 侧
 * {@code AuditService.record} 的"落库失败不影响动作结果"同一语义）。</p>
 */
@Slf4j
public class AuditOutbox {

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;

    public AuditOutbox(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;
        this.meters = meters;
    }

    public void record(AuditPayload payload) {
        try {
            redis.opsForStream().add(StreamKeys.auditPending(),
                    Map.of(AuditPayloadCodec.FIELD, AuditPayloadCodec.write(payload)));
            redis.opsForStream().trim(StreamKeys.auditPending(), StreamKeys.MAX_LEN);
        } catch (RuntimeException e) {
            meters.counter("marketing.audit.outbox.error").increment();
            log.warn("[audit] 投递失败（这条审计会丢失，但业务动作已完成）action={}, resource={}#{}, cause={}",
                    payload.action(), payload.resourceType(), payload.resourceId(), e.toString());
        }
    }
}
