package com.example.marketing.admin.controller;

import com.example.marketing.admin.audit.AuditRecord;
import com.example.marketing.admin.audit.AuditService;
import com.example.marketing.admin.infrastructure.entity.AdminSessionEntity;
import com.example.marketing.common.security.AdminPrincipal;
import com.example.marketing.common.security.AdminRoles;
import com.example.marketing.admin.service.AdminIdentityService;
import com.example.marketing.admin.service.AdminSessionService;
import com.example.marketing.common.api.PageQuery;
import com.example.marketing.common.api.PageResult;
import com.example.marketing.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话面：在线列表与强制下线。
 *
 * <p>{@code mine=true} 让普通后台用户只看自己的会话（改密前自查都在哪些设备上登录过），
 * 不带该参数则是全局视图，只有 admin 能取。</p>
 */
@RestController
@RequestMapping("/api/admin/sessions")
@RequiredArgsConstructor
public class AdminSessionController {

    private final AdminSessionService sessionService;
    private final AdminIdentityService identityService;
    private final AuditService auditService;

    @GetMapping
    public Result<PageResult<AdminSessionEntity>> list(
            HttpServletRequest request,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(defaultValue = "false") boolean mine) {
        // 看别人的会话是管理动作，看自己的只是查自己
        AdminPrincipal actor = mine
                ? identityService.resolve(request)
                : identityService.require(request, AdminRoles.ADMIN);
        Long userId = mine ? actor.uid() : null;
        return Result.ok(sessionService.listOnline(PageQuery.of(page, size), userId));
    }

    /** 管理员强制下线某个会话 */
    @DeleteMapping("/{jti}")
    public Result<Void> forceLogout(HttpServletRequest request, @PathVariable String jti) {
        AdminPrincipal actor = identityService.require(request, AdminRoles.ADMIN);
        sessionService.revoke(jti, "FORCE_LOGOUT:" + actor.username());
        auditService.record(AuditRecord.ofAction(actor.uid(), actor.username(), actor.role(),
                "session.force_logout", "session", jti, ClientIp.of(request)));
        return Result.ok();
    }
}
