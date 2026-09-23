package com.example.marketing.admin.audit;

import com.example.marketing.common.audit.AuditPayload;
import com.example.marketing.common.audit.AuditPayloadCodec;
import com.example.marketing.common.transport.StreamKeys;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 把 {@code mkt:audit:pending} 里的审计搬进 {@code admin_audit_log}。
 *
 * <p>为什么必须有它：③ 之后改预算/改库存的是业务进程，它们连不上这张表（每服务一库档
 * 根本没有权限）。搬运是<b>整条后台写留痕链路里唯一会静默丢数据的一环</b>，
 * 所以用消费组 + XACK（未确认的条目留在 PEL 里、能被 ④ 报成 pending 数），
 * 而不是"读一把就删"。</p>
 *
 * <p>用自起的 daemon 线程而不是 {@code @Scheduled}：admin 独立进程没有
 * {@code @EnableScheduling}（只有 standalone 有），漏加开关的表现是"审计永远不落表"——
 * 与 ⑤ 的轮询器同一个理由。</p>
 */
@Slf4j
@Component
public class AuditOutboxDrainer {

    /** 单批上限：5 秒一轮，一轮一万条也够消化任何人工操作量 */
    static final int BATCH = 500;

    private final StringRedisTemplate redis;
    private final AuditService auditService;
    private final MeterRegistry meters;
    private final long pollSeconds;
    /** 每个进程一个消费者名：同组多实例会分摊消息，不会一条审计被两个 admin 各落一遍 */
    private final String consumerName = "admin-" + UUID.randomUUID().toString().substring(0, 8);

    private volatile ScheduledExecutorService scheduler;

    public AuditOutboxDrainer(StringRedisTemplate redis, AuditService auditService,
                              MeterRegistry meters,
                              @Value("${marketing.audit.drain-seconds:${CONFIG_POLL_SECONDS:5}}") long pollSeconds) {
        this.redis = redis;
        this.auditService = auditService;
        this.meters = meters;
        this.pollSeconds = Math.max(1L, pollSeconds);
    }

    @PostConstruct
    public void start() {
        ensureGroup();
        drainOnce();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mkt-audit-drain");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::drainOnce, pollSeconds, pollSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
    }

    /**
     * 建组从 <b>0</b> 开始，而不是默认的"最新消息"。
     *
     * <p>默认那个口径下，先投递后建组（第一次部署、或键比消费组先存在）的那批审计
     * 永远不会被投给消费者 —— 它们不是脏数据，是<b>从没被读过的真审计</b>。
     * 从 0 建组会让它们进表，代价是重启时重复读一遍已删的（删了就没了），可接受。</p>
     */
    void ensureGroup() {
        try {
            redis.opsForStream().createGroup(StreamKeys.auditPending(),
                    ReadOffset.from("0"), StreamKeys.ADMIN_DRAIN_GROUP);
        } catch (RuntimeException e) {
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")) {
                log.warn("[audit] 建消费组失败（drain 仍会尝试读）: {}", e.toString());
            }
        }
    }

    /** 读一批 → 逐条落表 → 逐条 XACK。任何异常都吃掉，留给下一轮：Redis 抖一下不该停掉留痕 */
    void drainOnce() {
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    org.springframework.data.redis.connection.stream.Consumer
                            .from(StreamKeys.ADMIN_DRAIN_GROUP, consumerName),
                    StreamReadOptions.empty().count(BATCH),
                    StreamOffset.create(StreamKeys.auditPending(), ReadOffset.lastConsumed()));
            if (records == null || records.isEmpty()) {
                return;
            }
            for (MapRecord<String, Object, Object> record : records) {
                drainOne(record);
            }
        } catch (RuntimeException e) {
            meters.counter("marketing.audit.drain.error").increment();
            log.warn("[audit] drain 本轮失败（条目留在 PEL 里，下一轮再来）: {}", e.toString());
        }
    }

    private void drainOne(MapRecord<String, Object, Object> record) {
        Object raw = record.getValue() == null ? null : record.getValue().get(AuditPayloadCodec.FIELD);
        Optional<AuditPayload> payload = AuditPayloadCodec.read(raw == null ? null : String.valueOf(raw));
        if (payload.isEmpty()) {
            // 认不出的一律记数 + 告警 + 确认：留着它只会让每轮都重新读到同一条脏数据
            meters.counter("marketing.audit.drain.skipped").increment();
            log.warn("[audit] 载荷无法解析，已跳过并确认 id={}", record.getId());
        } else {
            auditService.recordPayload(payload.get(),
                    java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(
                            payload.get().epochSecond()), java.time.ZoneId.systemDefault()));
            // 计的是"搬动过几条"，不是"确认落库几条"：AuditService 沿用 ② 的语义，
            // INSERT 失败只告警不外溢。④ 要的正是这个数与 XLEN 的对比（堆了多少 vs 过了多少）
            meters.counter("marketing.audit.drained").increment();
        }
        // 先 ACK 再删：反过来会在"已删未确认"的窗口里让这条审计彻底消失（无 DB 行、无 PEL 记录）。
        // 删的原因很简单：MySQL 才是审计的账本，Redis 这条只是总线；
        // 不删则 XLEN 永远只增，MAXLEN 会把最旧的真审计挤掉——那才是丢数据。
        // 与 LITE 消息通道的"消费完 XDEL、跑完 XLEN 恒 0"是同一手法。
        redis.opsForStream().acknowledge(StreamKeys.auditPending(),
                StreamKeys.ADMIN_DRAIN_GROUP, record.getId());
        redis.opsForStream().delete(StreamKeys.auditPending(), record.getId());
    }
}
