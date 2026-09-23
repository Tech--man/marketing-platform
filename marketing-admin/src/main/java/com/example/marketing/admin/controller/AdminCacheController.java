package com.example.marketing.admin.controller;

import com.example.marketing.common.web.ClientIp;
import com.example.marketing.common.security.AdminRoles;
import com.example.marketing.admin.service.AdminIdentityService;
import com.example.marketing.common.api.ErrorCode;
import com.example.marketing.common.api.Result;
import com.example.marketing.common.cache.CacheReheatRegistry;
import com.example.marketing.common.cache.CacheReheater;
import com.example.marketing.common.exception.BizException;
import com.example.marketing.admin.audit.AuditRecord;
import com.example.marketing.admin.audit.AuditService;
import com.example.marketing.common.security.AdminPrincipal;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
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
 * <p><b>形态限制（诚实声明）</b>：注册表是从本 JVM 的 Bean 里收集的。LITE 聚合形态下
 * 三个 reheater 与后台在同一进程，这里能刷全部三类；FULL 分进程形态下 admin 进程里
 * 一个都没有，{@code types()} 为空，调用会拿到 {@code 41010 本形态不适用} 并附可用类型清单
 * （也是空）。用 41010 而不是 41000：41000 是业务失败，运营看到它会去找业务方，
 * 而这里真正该做的是换形态执行（母版风险 #4）。跨进程<b>回执</b>属于 ③
 * （写库 + {@code mkt:reheat:pending/ack}，母版 §6.3）——⑤ 交付的只是那把轮询器本身。
 * 与其偷偷做个只在一档能用的按钮，不如让它显式报错。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/cache")
@RequiredArgsConstructor
public class AdminCacheController {

    private final CacheReheatRegistry registry;
    private final AdminIdentityService identityService;
    private final AuditService auditService;

    @GetMapping("/types")
    public Result<List<String>> types(
            HttpServletRequest request) {
        identityService.require(request);
        return Result.ok(registry.types());
    }

    /** force=false 只补缺失键，force=true 删了重建（运营改完配置走这条） */
    @PostMapping("/reheat")
    public Result<CacheReheater.Result> reheat(
            HttpServletRequest request,
            @RequestParam String type,
            @RequestParam String key,
            @RequestParam(defaultValue = "true") boolean force) {
        if (registry.types().isEmpty()) {
            // 被拒的运维动作同样要留痕："有人试过刷这个键"本身就是审计要记的事，
            // 只记成功会让拒绝在日志里彻底隐形。
            AdminPrincipal denied = identityService.require(request, AdminRoles.OPERATIONAL);
            auditService.record(new AuditRecord(denied.uid(), denied.username(), denied.role(),
                    "cache.reheat", type, key, "POST", "/api/admin/cache/reheat",
                    "type=" + type + ", key=" + key + ", force=" + force,
                    ErrorCode.FORM_NOT_APPLICABLE.getCode(), "本进程无 reheater", ClientIp.of(request), 0));
            throw BizException.of(ErrorCode.FORM_NOT_APPLICABLE,
                    "当前进程没有任何缓存重预热实现（FULL 分进程形态下 reheater 在业务服务里），"
                            + "请在 owning 服务上执行，或等 ③ 的跨进程重预热回执");
        }
        AdminPrincipal actor = identityService.require(request, AdminRoles.OPERATIONAL);
        CacheReheater.Result result = registry.reheat(type, key, force);
        auditService.record(new AuditRecord(actor.uid(), actor.username(), actor.role(),
                "cache.reheat", result.type(), result.key(), "POST", "/api/admin/cache/reheat",
                "type=" + type + ", key=" + key + ", force=" + force, 0, "",
                ClientIp.of(request), 0));
        log.info("[admin] 重预热 type={}, key={}, before={}, after={}, force={}, actor={}",
                result.type(), result.key(), result.before(), result.after(), force, actor.username());
        return Result.ok(result);
    }
}
