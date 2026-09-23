package com.example.marketing.activity.controller;

import com.example.marketing.activity.domain.ActivityEvent;
import com.example.marketing.activity.dto.ActivityView;
import com.example.marketing.activity.dto.BudgetUpdateRequest;
import com.example.marketing.activity.dto.CreateActivityRequest;
import com.example.marketing.activity.dto.GrayUpdateRequest;
import com.example.marketing.activity.infrastructure.entity.ActivityEntity;
import com.example.marketing.activity.service.ActivityService;
import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.api.Result;
import com.example.marketing.common.audit.AuditOutbox;
import com.example.marketing.common.audit.AuditPayload;
import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.common.security.AdminRequestIdentity;
import com.example.marketing.common.security.AdminRoles;
import com.example.marketing.common.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 活动管理面（③）。路径在 {@code /api/admin/**} 下但**由 activity 进程自己发布**：
 * 预算写与它必须的 force 重预热不能跨进程拆开（母版 §6.0/§6.3），
 * 而 admin 进程既没有业务库写权限、也不该有。
 *
 * <p>身份只认签名 token：本服务在 FULL 进程形态监听 {@code *:8081}、LITE 的 standalone
 * 8085 发布到宿主机，把裸 {@code X-Admin-Role} 当授权等于同网段谁都能改预算
 * （段内 spec §3.2）。</p>
 *
 * <p>审计经 {@code mkt:audit:pending} 投递给 admin 落表：before/after 只有这个进程知道，
 * 而 {@code admin_audit_log} 的所有权不下放。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/activities")
@RequiredArgsConstructor
public class ActivityAdminController {

    private static final String RESOURCE = "activity";

    private final ActivityService activityService;
    private final AdminRequestIdentity identity;
    private final AuditOutbox outbox;

    /** 列表：任意后台角色可读（VO 带 version，编辑时要回传） */
    @GetMapping
    public Result<PageResult<ActivityView>> list(@RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size,
                                                 @RequestParam(required = false) String status,
                                                 HttpServletRequest request) {
        identity.require(request, AdminRoles.ADMIN, AdminRoles.OPERATOR, AdminRoles.READ_ONLY);
        return Result.ok(activityService.list(PageQuery.of(page, size), status));
    }

    @PostMapping
    public Result<ActivityView> create(@Valid @RequestBody CreateActivityRequest body,
                                       HttpServletRequest request) {
        AdminPrincipal actor = identity.require(request, AdminRoles.ADMIN);
        ActivityEntity created = activityService.create(body);
        audit(actor, "activity.create", created.getActivityNo(), request,
                "from=, to=budget=" + created.getBudgetAmount() + ", name=" + created.getName());
        return Result.ok(ActivityView.from(created));
    }

    /** 状态机流转：动作型端点，path 与 event 都进审计（谁把谁从哪推到哪） */
    @PostMapping("/{activityNo}/transition")
    public Result<ActivityView> transition(@PathVariable String activityNo,
                                           @RequestParam ActivityEvent event,
                                           HttpServletRequest request) {
        AdminPrincipal actor = identity.require(request, AdminRoles.ADMIN);
        String from = activityService.getByNo(activityNo).getStatus();
        ActivityEntity after = activityService.transition(activityNo, event);
        audit(actor, "activity.transition", activityNo, request,
                "from=" + from + ", to=" + after.getStatus() + ", event=" + event);
        return Result.ok(ActivityView.from(after));
    }

    @PutMapping("/{activityNo}/budget")
    public Result<ActivityView> updateBudget(@PathVariable String activityNo,
                                             @Valid @RequestBody BudgetUpdateRequest body,
                                             HttpServletRequest request) {
        AdminPrincipal actor = identity.require(request, AdminRoles.ADMIN);
        ActivityEntity before = activityService.getByNo(activityNo);
        ActivityEntity after = activityService.updateBudget(activityNo, body.budgetAmount(), body.version());
        audit(actor, "activity.budget.set", activityNo, request,
                "from=" + before.getBudgetAmount() + ", to=" + after.getBudgetAmount()
                        + ", version=" + after.getVersion());
        return Result.ok(ActivityView.from(after));
    }

    @PutMapping("/{activityNo}/gray")
    public Result<ActivityView> updateGray(@PathVariable String activityNo,
                                           @Valid @RequestBody GrayUpdateRequest body,
                                           HttpServletRequest request) {
        AdminPrincipal actor = identity.require(request, AdminRoles.ADMIN);
        ActivityEntity before = activityService.getByNo(activityNo);
        ActivityEntity after = activityService.updateGray(activityNo, body.grayPercent(),
                body.grayWhitelist(), body.version());
        audit(actor, "activity.gray.set", activityNo, request,
                "from=" + before.getGrayPercent() + ", to=" + after.getGrayPercent()
                        + ", whitelist=" + (after.getGrayWhitelist() == null ? "" : after.getGrayWhitelist())
                        + ", version=" + after.getVersion());
        return Result.ok(ActivityView.from(after));
    }

    /**
     * summary 只放关键字段的 from/to —— 不塞请求体原文：
     * admin 侧 {@code RequestSummary} 那套脱敏在 admin 模块里（② 的边界），
     * 而这里本来就有更精确的事实可写。
     */
    private void audit(AdminPrincipal actor, String action, String resourceId,
                       HttpServletRequest request, String summary) {
        outbox.record(new AuditPayload(actor.uid(), actor.username(), actor.role(), action, RESOURCE,
                resourceId, request.getMethod(), request.getRequestURI(), summary, 0, "",
                ClientIp.of(request), 0L, System.currentTimeMillis() / 1000));
    }
}
