package com.example.marketing.coupon.controller;

import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.api.Result;
import com.example.marketing.common.audit.AuditOutbox;
import com.example.marketing.common.audit.AuditPayload;
import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.common.security.AdminRequestIdentity;
import com.example.marketing.common.security.AdminRoles;
import com.example.marketing.common.web.ClientIp;
import com.example.marketing.coupon.dto.StockUpdateRequest;
import com.example.marketing.coupon.dto.StatusUpdateRequest;
import com.example.marketing.coupon.dto.TemplateCreateRequest;
import com.example.marketing.coupon.dto.TemplateView;
import com.example.marketing.coupon.infrastructure.entity.CouponTemplateEntity;
import com.example.marketing.coupon.service.CouponTemplateService;
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
 * 券模板管理面（③）。今天模板只能靠 init.sql 种子写进库，这里补上创建与编辑能力。
 *
 * <p>与 activity 同一套约束：身份只认签名 token；库存写与键重建必须在同一个 service
 * 方法里（见 {@code CouponTemplateService.updateTotalStock}），admin 侧不得"写完 DB 再刷一把缓存"。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/coupon")
@RequiredArgsConstructor
public class CouponAdminController {

    private static final String RESOURCE = "coupon-template";

    private final CouponTemplateService templateService;
    private final AdminRequestIdentity identity;
    private final AuditOutbox outbox;

    @GetMapping("/templates")
    public Result<PageResult<TemplateView>> list(@RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size,
                                                 @RequestParam(required = false) String status,
                                                 HttpServletRequest request) {
        identity.require(request, AdminRoles.ADMIN, AdminRoles.OPERATOR, AdminRoles.READ_ONLY);
        return Result.ok(templateService.list(PageQuery.of(page, size), status));
    }

    @PostMapping("/templates")
    public Result<TemplateView> create(@Valid @RequestBody TemplateCreateRequest body,
                                       HttpServletRequest request) {
        AdminPrincipal actor = identity.require(request, AdminRoles.ADMIN);
        CouponTemplateEntity created = templateService.create(body);
        audit(actor, "coupon.template.create", created.getTemplateNo(), request,
                "from=, to=totalStock=" + created.getTotalStock() + ", faceValue=" + created.getFaceValue()
                        + ", version=" + created.getVersion());
        return Result.ok(TemplateView.from(created));
    }

    @PutMapping("/templates/{templateNo}/stock")
    public Result<TemplateView> updateStock(@PathVariable String templateNo,
                                            @Valid @RequestBody StockUpdateRequest body,
                                            HttpServletRequest request) {
        AdminPrincipal actor = identity.require(request, AdminRoles.ADMIN);
        CouponTemplateEntity before = templateService.getRequiringExists(templateNo);
        int from = before.getTotalStock();
        CouponTemplateEntity after = templateService.updateTotalStock(templateNo, body);
        audit(actor, "coupon.stock.set", templateNo, request,
                "from=" + from + ", to=" + after.getTotalStock() + ", version=" + after.getVersion());
        return Result.ok(TemplateView.from(after));
    }

    @PutMapping("/templates/{templateNo}/status")
    public Result<TemplateView> updateStatus(@PathVariable String templateNo,
                                             @Valid @RequestBody StatusUpdateRequest body,
                                             HttpServletRequest request) {
        AdminPrincipal actor = identity.require(request, AdminRoles.ADMIN);
        CouponTemplateEntity before = templateService.getRequiringExists(templateNo);
        String from = before.getStatus();
        CouponTemplateEntity after = templateService.updateStatus(templateNo, body);
        audit(actor, "coupon.status.set", templateNo, request,
                "from=" + from + ", to=" + after.getStatus() + ", version=" + after.getVersion());
        return Result.ok(TemplateView.from(after));
    }

    private void audit(AdminPrincipal actor, String action, String templateNo,
                       HttpServletRequest request, String summary) {
        outbox.record(new AuditPayload(actor.uid(), actor.username(), actor.role(), action, RESOURCE,
                templateNo, request.getMethod(), request.getRequestURI(), summary, 0, "",
                ClientIp.of(request), 0L, System.currentTimeMillis() / 1000));
    }
}
