package com.example.marketing.admin.controller;

import com.example.marketing.admin.audit.AuditService;
import com.example.marketing.admin.infrastructure.entity.AdminAuditLogEntity;
import com.example.marketing.admin.service.AdminIdentityService;
import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计查询。任何后台角色可读（含 read-only）：这张表里的敏感值已在写入前脱敏，
 * 而"谁能看到操作留痕"本身是审计的可信度的一部分 —— 只让 admin 看，
 * 就等于让最可能被审计的人独占解释权。
 */
@RestController
@RequestMapping("/api/admin/audits")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AuditService auditService;
    private final AdminIdentityService identityService;

    @GetMapping
    public Result<PageResult<AdminAuditLogEntity>> query(
            HttpServletRequest request,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId) {
        identityService.require(request);
        return Result.ok(auditService.query(PageQuery.of(page, size),
                actorId, action, resourceType, resourceId));
    }
}
