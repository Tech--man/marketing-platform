package com.example.marketing.admin.controller;

import com.example.marketing.admin.audit.AuditRecord;
import com.example.marketing.admin.audit.AuditService;
import com.example.marketing.admin.dto.ReheatReceipt;
import com.example.marketing.admin.service.AdminIdentityService;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.api.Result;
import com.example.marketing.common.cache.CacheReheatRegistry;
import com.example.marketing.common.cache.CacheReheater;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.common.reheat.ReheatCodec;
import com.example.marketing.common.reheat.ReheatPayloads;
import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.common.security.AdminRoles;
import com.example.marketing.common.transport.StreamKeys;
import com.example.marketing.common.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 缓存重预热入口 —— 地雷 A（改了 DB 不生效）与 E（缺键后按全额重建导致预算回涨）的运维出口。
 *
 * <p>只分发、不解释：具体公式在 {@link CacheReheater} 的各实现里（预算在 activity、
 * 券在 coupon、桶在 seckill），这里连"余量怎么算"都不知道。</p>
 *
 * <p><b>两条执行路径（③ T8）</b>：</p>
 * <ul>
 *   <li>本进程有该 type 的 reheater（LITE 聚合、dev）→ 同步执行并直接返回 before/after，
 *       与 ①② 的行为逐字节相同；</li>
 *   <li>本进程没有（FULL 分进程的 admin）→ 投进 {@code mkt:reheat:{type}:pending}，
 *       返回 {@code DISPATCHED} + 一个 id，回执由 owning 服务写回，
 *       {@code GET /cache/reheat/ack} 查。</li>
 * </ul>
 *
 * <p>仍然保留 {@code 41010} 的那一种情况：该 type 在 Redis 里<b>连消费组都没有</b>，
 * 说明集群里没有任何进程能执行它（owning 服务没起来）。此时投递注定无人认领，
 * 与其让它变成一条永远 DISPATCHED 的回执，不如当场显式报错——
 * "看起来提交了但永远没动静"正是本项目最贵的那类静默不一致。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/cache")
@RequiredArgsConstructor
public class AdminCacheController {

    private final CacheReheatRegistry registry;
    private final AdminIdentityService identityService;
    private final AuditService auditService;
    private final StringRedisTemplate redis;

    @GetMapping("/types")
    public Result<List<String>> types(HttpServletRequest request) {
        identityService.require(request);
        return Result.ok(registry.types());
    }

    /** force=false 只补缺失键，force=true 删了重建（运营改完配置走这条） */
    @PostMapping("/reheat")
    public Result<ReheatReceipt> reheat(HttpServletRequest request,
                                         @RequestParam String type,
                                         @RequestParam String key,
                                         @RequestParam(defaultValue = "true") boolean force) {
        AdminPrincipal actor = identityService.require(request, AdminRoles.OPERATIONAL);
        if (registry.types().contains(type)) {
            CacheReheater.Result result = registry.reheat(type, key, force);
            audit(actor, type, key, force, 0, "", "sync", request);
            log.info("[admin] 重预热(本进程) type={}, key={}, before={}, after={}, force={}, actor={}",
                    result.type(), result.key(), result.before(), result.after(), force, actor.username());
            return Result.ok(ReheatReceipt.done(result));
        }
        if (!hasConsumer(type)) {
            // 拒绝也要留痕：只记成功会让"有人试过但没成"在审计里彻底隐形
            audit(actor, type, key, force, ErrorCode.FORM_NOT_APPLICABLE.getCode(), "无人认领",
                    "rejected", request);
            throw BizException.of(ErrorCode.FORM_NOT_APPLICABLE,
                    "集群内没有任何进程注册 type=" + type + " 的重预热实现（消费组不存在）。"
                            + "请在 owning 服务上执行，或先把它起起来");
        }
        String id = dispatch(type, key, force, actor.username());
        // id 进摘要：没有它，审计里那一行和回执之间的线就断了
        audit(actor, type, key, force, 0, "", "dispatched id=" + id, request);
        log.info("[admin] 重预热已投递 type={}, key={}, force={}, id={}, actor={}",
                type, key, force, id, actor.username());
        return Result.ok(ReheatReceipt.dispatched(id, type, key, force));
    }

    /** 回执查询：DISPATCHED 不等于失败，但也不等于成功——只有 DONE 才是刷过了 */
    @GetMapping("/reheat/ack")
    public Result<ReheatReceipt> ack(HttpServletRequest request,
                                     @RequestParam String type,
                                     @RequestParam String id) {
        identityService.require(request);
        String ackJson = redis.opsForValue().get(StreamKeys.reheatAck(type, id));
        if (ackJson != null) {
            return ReheatCodec.readAck(ackJson)
                    .map(ack -> Result.ok(ReheatReceipt.fromAck(ack)))
                    .orElseGet(() -> Result.ok(ReheatReceipt.unknown(id, type)));
        }
        // 标记的值就是当初那个 key：回执端不必让前端把参数再传一遍
        String queuedKey = redis.opsForValue().get(StreamKeys.reheatSent(type, id));
        if (queuedKey != null) {
            return Result.ok(ReheatReceipt.stillQueued(id, type, queuedKey));
        }
        return Result.ok(ReheatReceipt.unknown(id, type));
    }

    /** 有消费组 = 集群里有进程认领这个 type（组由各 owning 服务启动时自建） */
    private boolean hasConsumer(String type) {
        try {
            var info = redis.opsForStream().groups(StreamKeys.reheatPending(type));
            return info != null && info.size() > 0;
        } catch (RuntimeException e) {
            // 键还不存在时 XINFO 会报错——那正是"没人认领"
            log.debug("[admin] 查消费组失败 type={}: {}", type, e.toString());
            return false;
        }
    }

    /**
     * 投一条待执行的重预热。顺序：发号 → 写"已投递"标记 → XADD。
     *
     * <p>标记先于投递：反过来会出现"已入队但没有标记"的瞬间，此时回执查不到、
     * 标记也没有，前端只能看到 UNKNOWN——把\"还没执行\"误报成\"根本没这回事\"。</p>
     */
    private String dispatch(String type, String key, boolean force, String actor) {
        String id = String.valueOf(redis.opsForValue().increment(StreamKeys.REHEAT_SEQUENCE));
        ReheatPayloads.Request request = new ReheatPayloads.Request(
                id, type, key, force, actor, System.currentTimeMillis() / 1000);
        redis.opsForValue().set(StreamKeys.reheatSent(type, id), key, StreamKeys.REHEAT_RECEIPT_TTL);
        redis.opsForStream().add(StreamKeys.reheatPending(type),
                java.util.Map.of(ReheatCodec.FIELD, ReheatCodec.writeRequest(request)));
        return id;
    }

    private void audit(AdminPrincipal actor, String type, String key, boolean force,
                       int code, String error, String outcome, HttpServletRequest request) {
        auditService.record(new AuditRecord(actor.uid(), actor.username(), actor.role(),
                "cache.reheat", type, key, "POST", "/api/admin/cache/reheat",
                "type=" + type + ", key=" + key + ", force=" + force + ", " + outcome, code, error,
                ClientIp.of(request), 0));
    }
}
