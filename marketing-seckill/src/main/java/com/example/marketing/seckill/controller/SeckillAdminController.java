package com.example.marketing.seckill.controller;

import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.api.Result;
import com.example.marketing.common.audit.AuditOutbox;
import com.example.marketing.common.audit.AuditPayload;
import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.common.security.AdminRequestIdentity;
import com.example.marketing.common.security.AdminRoles;
import com.example.marketing.common.web.ClientIp;
import com.example.marketing.seckill.dto.SeckillActivityCreateRequest;
import com.example.marketing.seckill.dto.SeckillActivityView;
import com.example.marketing.seckill.dto.SeckillStatusRequest;
import com.example.marketing.seckill.dto.StockEditRequest;
import com.example.marketing.seckill.infrastructure.entity.SeckillActivityEntity;
import com.example.marketing.seckill.service.SeckillAdminService;
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
 * 秒杀活动管理面（③）：创建、上下线、库存。
 *
 * <p>放在 seckill 进程而不是 admin：库存写与分桶重建必须同事务，
 * 而分桶公式与"仅 ONLINE 可 force 重建"的守卫只在 {@code SeckillWarmUpService} 里有一份。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/seckill")
@RequiredArgsConstructor
public class SeckillAdminController {

    private static final String RESOURCE = "seckill-activity";

    private final SeckillAdminService adminService;
    private final AdminRequestIdentity identity;
    private final com.example.marketing.common.message.LocalMessageService localMessageService;
    private final AuditOutbox outbox;

    @GetMapping("/activities")
    public Result<PageResult<SeckillActivityView>> list(@RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size,
                                                        @RequestParam(required = false) String status,
                                                        HttpServletRequest request) {
        identity.require(request, AdminRoles.ADMIN, AdminRoles.OPERATOR, AdminRoles.READ_ONLY);
        return Result.ok(adminService.list(PageQuery.of(page, size), status));
    }

    @PostMapping("/activities")
    public Result<SeckillActivityView> create(@Valid @RequestBody SeckillActivityCreateRequest body,
                                              HttpServletRequest request) {
        AdminPrincipal actor = identity.require(request, AdminRoles.ADMIN);
        SeckillActivityEntity created = adminService.create(body);
        audit(actor, "seckill.activity.create", created.getActivityNo(), request,
                "from=, to=totalStock=" + created.getTotalStock() + ", price=" + created.getSeckillPrice()
                        + ", status=" + created.getStatus() + ", version=" + created.getVersion());
        return Result.ok(SeckillActivityView.from(created));
    }

    @PutMapping("/activities/{activityNo}/stock")
    public Result<SeckillActivityView> updateStock(@PathVariable String activityNo,
                                                   @Valid @RequestBody StockEditRequest body,
                                                   HttpServletRequest request) {
        AdminPrincipal actor = identity.require(request, AdminRoles.ADMIN);
        SeckillActivityEntity before = adminService.existing(activityNo);
        int from = before.getTotalStock();
        SeckillActivityEntity after = adminService.updateStock(activityNo, body);
        audit(actor, "seckill.stock.set", activityNo, request,
                "from=" + from + ", to=" + after.getTotalStock()
                        + ", status=" + after.getStatus() + ", version=" + after.getVersion());
        return Result.ok(SeckillActivityView.from(after));
    }

    @PutMapping("/activities/{activityNo}/status")
    public Result<SeckillActivityView> updateStatus(@PathVariable String activityNo,
                                                    @Valid @RequestBody SeckillStatusRequest body,
                                                    HttpServletRequest request) {
        AdminPrincipal actor = identity.require(request, AdminRoles.ADMIN);
        SeckillActivityEntity before = adminService.existing(activityNo);
        String from = before.getStatus();
        SeckillActivityEntity after = adminService.updateStatus(activityNo, body);
        audit(actor, "seckill.status.set", activityNo, request,
                "from=" + from + ", to=" + after.getStatus() + ", version=" + after.getVersion());
        return Result.ok(SeckillActivityView.from(after));
    }

    /**
     * 重驱动本地消息死信（2026-09-29 审查，对账兜底的人工通道）：FAILED 是终态，
     * 补偿定时器不再扫它；这里置回 PENDING 由定时器重新投递，重投安全由消费端
     * 幂等保证。驱动条数进审计。
     */
    @PostMapping("/messages/redrive")
    public Result<Integer> redriveMessages(HttpServletRequest request) {
        AdminPrincipal actor = identity.require(request, AdminRoles.ADMIN);
        int driven = localMessageService.redriveFailed(com.example.marketing.common.mq.MqTopics.TOPIC_SECKILL_ORDER);
        audit(actor, "seckill.messages.redrive", "local_message", request,
                "topic=" + com.example.marketing.common.mq.MqTopics.TOPIC_SECKILL_ORDER + ", driven=" + driven);
        return Result.ok(driven);
    }

    private void audit(AdminPrincipal actor, String action, String activityNo,
                       HttpServletRequest request, String summary) {
        outbox.record(new AuditPayload(actor.uid(), actor.username(), actor.role(), action, RESOURCE,
                activityNo, request.getMethod(), request.getRequestURI(), summary, 0, "",
                ClientIp.of(request), 0L, System.currentTimeMillis() / 1000));
    }
}
