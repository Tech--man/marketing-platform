package com.example.marketing.discount.controller;

import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.api.Result;
import com.example.marketing.common.audit.AuditOutbox;
import com.example.marketing.common.audit.AuditPayload;
import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.common.security.AdminRequestIdentity;
import com.example.marketing.common.security.AdminRoles;
import com.example.marketing.common.web.ClientIp;
import com.example.marketing.discount.dto.RuleSaveRequest;
import com.example.marketing.discount.dto.RuleView;
import com.example.marketing.discount.service.RuleAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 优惠规则管理面（③）：原先的 {@code GET/POST /api/discount/rules} 搬到这里。
 *
 * <p>搬的真正理由不是"路径不好看"：{@code GET} 那条把<b>整套规则 DSL</b>（门槛、互斥组、
 * 每人限领、阶梯明细）原样交给共享 demo token 的任何人 —— 注释里写着"管理端"，
 * 却挂在 C 路径上（母版 §6.1）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/discount")
@RequiredArgsConstructor
public class DiscountAdminController {

    private static final String RESOURCE = "promo-rule";

    private final RuleAdminService ruleAdminService;
    private final AdminRequestIdentity identity;
    private final AuditOutbox outbox;

    @GetMapping("/rules")
    public Result<PageResult<RuleView>> list(@RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size,
                                             @RequestParam(required = false) String status,
                                             HttpServletRequest request) {
        identity.require(request, AdminRoles.ADMIN, AdminRoles.OPERATOR, AdminRoles.READ_ONLY);
        return Result.ok(ruleAdminService.list(PageQuery.of(page, size), status));
    }

    /**
     * 按 ruleNo upsert：带 version 是编辑、不带是新建（不需要两个端点）。
     * 只有 admin 能改规则 —— operator 可写运维动作，但规则是业务配置（与 ⑤ 的阈值同一把尺子）。
     */
    @PostMapping("/rules")
    public Result<RuleView> save(@Valid @RequestBody RuleSaveRequest body,
                                 @RequestParam(required = false) Integer version,
                                 HttpServletRequest request) {
        AdminPrincipal actor = identity.require(request, AdminRoles.ADMIN);
        RuleView before = ruleAdminService.findView(body.getRuleNo());
        RuleView after = ruleAdminService.save(body, version);
        audit(actor, before == null ? "discount.rule.create" : "discount.rule.update",
                body.getRuleNo(), request,
                "from=" + (before == null ? "" : summaryOf(before))
                        + ", to=" + summaryOf(after) + ", version=" + after.version());
        return Result.ok(after);
    }

    /**
     * 审计摘要只放关键列。整条 DSL 可能很长（ladderSteps、requiredTags 都是集合），
     * 而 {@code request_summary} 是定长列 —— 截断后的 JSON 既难读又可能截掉真正变了的那一段，
     * 所以这里宁可只记 name/type/priority/status 四列。
     */
    private String summaryOf(RuleView v) {
        return "{name=" + v.name() + ", type=" + v.ruleType()
                + ", priority=" + v.priority() + ", status=" + v.status() + "}";
    }

    private void audit(AdminPrincipal actor, String action, String ruleNo,
                       HttpServletRequest request, String summary) {
        outbox.record(new AuditPayload(actor.uid(), actor.username(), actor.role(), action, RESOURCE,
                ruleNo, request.getMethod(), request.getRequestURI(), summary, 0, "",
                ClientIp.of(request), 0L, System.currentTimeMillis() / 1000));
    }
}
